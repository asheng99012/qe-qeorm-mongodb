package com.github.vincentrussell.query.mongodb.sql.converter.util;

import com.alibaba.fastjson.JSON;
import com.github.vincentrussell.query.mongodb.sql.converter.FieldType;
import com.github.vincentrussell.query.mongodb.sql.converter.ParseException;
import com.github.vincentrussell.query.mongodb.sql.converter.Token;
import com.github.vincentrussell.query.mongodb.sql.converter.WhereCauseProcessor;
import com.google.common.collect.Lists;
import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.*;
import org.bson.Document;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.google.common.base.MoreObjects.firstNonNull;

public class SqlUtils {
    private static Pattern SURROUNDED_IN_QUOTES = Pattern.compile("^\"(.+)*\"$");
    private static Pattern LIKE_RANGE_REGEX = Pattern.compile("(\\[.+?\\])");
    private static final String REGEXMATCH_FUNCTION = "regexMatch";
    private static final String OBJECTID_FUNCTION = "objectId";
    private static final List<String> SPECIALTY_FUNCTIONS = Arrays.asList(REGEXMATCH_FUNCTION, OBJECTID_FUNCTION);
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    private static final DateTimeFormatter YY_MM_DDFORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd");
    private static final DateTimeFormatter YYMMDDFORMATTER = DateTimeFormat.forPattern("yyyyMMdd");

    private static final Collection<DateTimeFormatter> FORMATTERS = Collections.unmodifiableList(Arrays.asList(
            ISODateTimeFormat.dateTime(),
            YY_MM_DDFORMATTER,
            YYMMDDFORMATTER));

    private SqlUtils() {
    }

    public static Object getValue(Expression expression) {
        if (expression instanceof DateValue)
            return ((DateValue) expression).getValue();
        if (expression instanceof DoubleValue)
            return ((DoubleValue) expression).getValue();
        if (expression instanceof LongValue)
            return ((LongValue) expression).getValue();
        if (expression instanceof NullValue)
            return expression.toString();
        if (expression instanceof TimestampValue)
            return ((TimestampValue) expression).getValue();
        if (expression instanceof TimeValue)
            return ((TimeValue) expression).getValue();
        if (expression instanceof StringValue) {
            StringValue value = (StringValue) expression;
            String val = value.getValue();
            if (val.startsWith("[") || val.startsWith("{"))
                return JSON.parse(val);
            if (val.matches("^\\d{4}-\\d{1,2}-\\d{1,2}(\\s\\d{1,2}:\\d{1,2}:\\d{1,2}(.\\d{1,5})?)?$")) {
                if (val.indexOf(" ") == -1) {
                    val = val + " 00:00:00";
                }
                return parseNaturalLanguageDate(val);
            }
            return val;
        }
        if (expression instanceof Function) {
            Function function = (Function) expression;
            if (function.getName().equals("now"))
                return new Date();
        }
        return expression.toString();
    }

    public static String getStringValue(Expression expression) {
        if (StringValue.class.isInstance(expression)) {
            return ((StringValue) expression).getValue();
        } else if (Column.class.isInstance(expression)) {
            String columnName = expression.toString();
            Matcher matcher = SURROUNDED_IN_QUOTES.matcher(columnName);
            if (matcher.matches()) {
                return matcher.group(1);
            }
            return columnName;
        }
        return expression.toString();
    }

    public static Object getValue(Expression incomingExpression, Expression otherSide,
                                  FieldType defaultFieldType,
                                  Map<String, FieldType> fieldNameToFieldTypeMapping) throws ParseException {
        FieldType fieldType = otherSide != null ? firstNonNull(fieldNameToFieldTypeMapping.get(getStringValue(otherSide)),
                defaultFieldType) : FieldType.UNKNOWN;
        if (LongValue.class.isInstance(incomingExpression)) {
            return normalizeValue((((LongValue) incomingExpression).getValue()), fieldType);
        } else if (SignedExpression.class.isInstance(incomingExpression)) {
            return normalizeValue((((SignedExpression) incomingExpression).toString()), fieldType);
        } else if (StringValue.class.isInstance(incomingExpression)) {
            return normalizeValue((((StringValue) incomingExpression).getValue()), fieldType);
        } else if (Column.class.isInstance(incomingExpression)) {
            return normalizeValue(getStringValue(incomingExpression), fieldType);
        } else {
            throw new ParseException("can not parseNaturalLanguageDate: " + incomingExpression.toString());
        }
    }

    public static boolean isSpecialtyFunction(Expression incomingExpression) {
        if (incomingExpression == null) {
            return false;
        }

        if (Function.class.isInstance(incomingExpression) && containsIgnoreCase(SPECIALTY_FUNCTIONS, ((Function) incomingExpression).getName())) {
            return true;
        }

        return false;
    }

    public static boolean containsIgnoreCase(List<String> list, String soughtFor) {
        for (String current : list) {
            if (current.equalsIgnoreCase(soughtFor)) {
                return true;
            }
        }
        return false;
    }

    public static Object parseFunctionArguments(final ExpressionList parameters,
                                                final FieldType defaultFieldType,
                                                final Map<String, FieldType> fieldNameToFieldTypeMapping) {
        if (parameters == null) {
            return null;
        } else if (parameters.getExpressions().size() == 1) {
            return getStringValue(parameters.getExpressions().get(0));
        } else {
            return Lists.newArrayList(Lists.transform(parameters.getExpressions(),
                    new com.google.common.base.Function<Expression, Object>() {
                        @Override
                        public Object apply(Expression expression) {
                            try {
                                return getValue(expression, null, defaultFieldType,
                                        fieldNameToFieldTypeMapping);
                            } catch (ParseException e) {
                                return getStringValue(expression);
                            }
                        }
                    }));
        }
    }


    public static Object normalizeValue(Object value, FieldType fieldType) throws ParseException {
        if (fieldType == null || FieldType.UNKNOWN.equals(fieldType)) {
            Object bool = forceBool(value);
            return (bool != null) ? bool : value;
        } else {
            if (FieldType.STRING.equals(fieldType)) {
                return fixDoubleSingleQuotes(forceString(value));
            }
            if (FieldType.NUMBER.equals(fieldType)) {
                return forceNumber(value);
            }
            if (FieldType.DATE.equals(fieldType)) {
                return forceDate(value);
            }
            if (FieldType.BOOLEAN.equals(fieldType)) {
                return Boolean.valueOf(value.toString());
            }
        }
        throw new ParseException("could not normalize value:" + value);
    }

    public static long getLimit(Limit limit) throws ParseException {
        if (limit != null && limit.getRowCount() != null) {
            String rowCountString = SqlUtils.getStringValue(limit.getRowCount());
            BigInteger bigInt = new BigInteger(rowCountString);
            isFalse(bigInt.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0, rowCountString + ": value is too large");
            return bigInt.longValue();
        }
        return -1;
    }

    public static long getOffset(Limit limit) throws ParseException {
        if (limit != null && limit.getOffset() != null) {
            String rowCountString = SqlUtils.getStringValue(limit.getOffset());
            BigInteger bigInt = new BigInteger(rowCountString);
            isFalse(bigInt.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0, rowCountString + ": value is too large");
            return bigInt.longValue();
        }
        return -1;
    }

    public static String fixDoubleSingleQuotes(final String regex) {
        return regex.replaceAll("''", "'");
    }

    public static boolean isSelectAll(List<SelectItem> selectItems) {
        if (selectItems != null && selectItems.size() == 1) {
            SelectItem firstItem = selectItems.get(0);
            return AllColumns.class.isInstance(firstItem);
        } else {
            return false;
        }
    }

    public static boolean isCountAll(List<SelectItem> selectItems) {
        if (selectItems != null && selectItems.size() == 1) {
            SelectItem firstItem = selectItems.get(0);
            if ((SelectExpressionItem.class.isInstance(firstItem))
                    && Function.class.isInstance(((SelectExpressionItem) firstItem).getExpression())) {
                Function function = (Function) ((SelectExpressionItem) firstItem).getExpression();

                if ("count(*)".equals(function.toString())) {
                    return true;
                }

            }
        }
        return false;
    }

    public static Object forceBool(Object value) {
        if (value.toString().equalsIgnoreCase("true") || value.toString().equalsIgnoreCase("false")) {
            return Boolean.valueOf(value.toString());
        }
        return null;
    }

    public static Object forceDate(Object value) throws ParseException {
        if (String.class.isInstance(value)) {
            for (DateTimeFormatter formatter : FORMATTERS) {
                try {
                    DateTime dt = formatter.parseDateTime((String) value);
                    return dt.toDate();
                } catch (Exception e) {
                    //noop
                }
            }
            try {
                return parseNaturalLanguageDate((String) value);
            } catch (Exception e) {
                //noop
            }

        }
        throw new ParseException("could not convert " + value + " to a date");
    }

    public static Date parseNaturalLanguageDate(String text) {
        Parser parser = new Parser();
        List<DateGroup> groups = parser.parse(text);
        for (DateGroup group : groups) {
            List<Date> dates = group.getDates();
            if (dates.size() > 0) {
                return dates.get(0);
            }
        }
        throw new IllegalArgumentException("could not natural language date: " + text);
    }

    public static Object forceNumber(Object value) throws ParseException {
        if (String.class.isInstance(value)) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e1) {
                try {
                    return Double.parseDouble((String) value);
                } catch (NumberFormatException e2) {
                    try {
                        return Float.parseFloat((String) value);
                    } catch (NumberFormatException e3) {
                        throw new ParseException("could not convert " + value + " to number");
                    }
                }
            }
        } else {
            return value;
        }
    }

    public static String forceString(Object value) {
        if (String.class.isInstance(value)) {
            return (String) value;
        } else {
            return "" + value + "";
        }
    }

    public static ParseException convertParseException(net.sf.jsqlparser.parser.ParseException incomingException) {
        try {
            return new ParseException(new Token(incomingException.currentToken.kind,
                    incomingException.currentToken.image), incomingException.expectedTokenSequences,
                    incomingException.tokenImage);
        } catch (NullPointerException e1) {
            if (incomingException.getMessage().startsWith("Encountered \" \"(\" \"( \"\"")) {
                return new ParseException("Only one simple table name is supported.");
            }
            if (incomingException.getMessage().startsWith("Encountered unexpected token: \"=\" \"=\"")) {
                return new ParseException("unable to parse complete sql string. one reason for this is the use of double equals (==).");
            }
            if (incomingException.getMessage().contains("Was expecting:" + LINE_SEPARATOR +
                    "    \"SELECT\"")) {
                return new ParseException("Only select statements are supported.");
            }
            if (incomingException.getMessage() != null) {
                return new ParseException(incomingException.getMessage());
            }
            return new ParseException("Count not parseNaturalLanguageDate query.");
        }
    }


    public static String replaceRegexCharacters(String value) {
        String newValue = value.replaceAll("%", ".*")
                .replaceAll("_", ".{1}");

        Matcher m = LIKE_RANGE_REGEX.matcher(newValue);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, m.group(1) + "{1}");
        }
        m.appendTail(sb);

        return sb.toString();
    }

    public static List<String> getGroupByColumnReferences(PlainSelect plainSelect) {
        if (plainSelect.getGroupByColumnReferences() == null) {
            return Collections.emptyList();
        }
        return Lists.transform(plainSelect.getGroupByColumnReferences(), new com.google.common.base.Function<Expression, String>() {
            @Override
            public String apply(Expression expression) {
                return SqlUtils.getStringValue(expression);
            }
        });
    }

    public static String replaceGroup(String source, int groupToReplace, int groupOccurrence, String replacement) {
        Matcher m = LIKE_RANGE_REGEX.matcher(source);
        for (int i = 0; i < groupOccurrence; i++)
            if (!m.find()) return source; // pattern not met, may also throw an exception here
        return new StringBuilder(source).replace(m.start(groupToReplace), m.end(groupToReplace), replacement).toString();
    }

    public static ObjectIdFunction isObjectIdFunction(final WhereCauseProcessor whereCauseProcessor,
                                                      Expression incomingExpression) throws ParseException {
        if (ComparisonOperator.class.isInstance(incomingExpression)) {
            ComparisonOperator comparisonOperator = (ComparisonOperator) incomingExpression;
            String rightExpression = getStringValue(comparisonOperator.getRightExpression());
            if (Function.class.isInstance(comparisonOperator.getLeftExpression())) {
                Function function = ((Function) comparisonOperator.getLeftExpression());
                if ("objectid".equals(function.getName().toLowerCase())
                        && (function.getParameters().getExpressions().size() == 1)
                        && StringValue.class.isInstance(function.getParameters().getExpressions().get(0))) {
                    String column = getStringValue(function.getParameters().getExpressions().get(0));
                    return new ObjectIdFunction(column, rightExpression, comparisonOperator);
                }
            }
        } else if (InExpression.class.isInstance(incomingExpression)) {
            InExpression inExpression = (InExpression) incomingExpression;
            final Expression leftExpression = ((InExpression) incomingExpression).getLeftExpression();

            if (Function.class.isInstance(inExpression.getLeftExpression())) {
                Function function = ((Function) inExpression.getLeftExpression());
                if ("objectid".equals(function.getName().toLowerCase())
                        && (function.getParameters().getExpressions().size() == 1)
                        && StringValue.class.isInstance(function.getParameters().getExpressions().get(0))) {
                    String column = getStringValue(function.getParameters().getExpressions().get(0));
                    List<Object> rightExpression = Lists.transform(((ExpressionList)
                                    inExpression.getRightItemsList()).getExpressions(),
                            new com.google.common.base.Function<Expression, Object>() {
                                @Override
                                public Object apply(Expression expression) {
                                    try {
                                        return whereCauseProcessor.parseExpression(new Document(), expression, leftExpression);
                                    } catch (ParseException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            });
                    return new ObjectIdFunction(column, rightExpression, inExpression);
                }
            }
        }
        return null;
    }

    public static DateFunction isDateFunction(Expression incomingExpression) throws ParseException {
        if (ComparisonOperator.class.isInstance(incomingExpression)) {
            ComparisonOperator comparisonOperator = (ComparisonOperator) incomingExpression;
            String rightExpression = getStringValue(comparisonOperator.getRightExpression());
            if (rightExpression.matches("^\\d{4}-\\d{1,2}-\\d{1,2}(\\s\\d{1,2}:\\d{1,2}:\\d{1,2}(.\\d{1,5})?)?$")) {
                if (rightExpression.indexOf(" ") == -1) {
                    rightExpression = rightExpression + " 00:00:00";
                }
                String column = ((Column)comparisonOperator.getLeftExpression()).getColumnName();
                DateFunction dateFunction = null;
                try {
                    String format = "natural";
                    dateFunction = new DateFunction(format, rightExpression, column);
                    dateFunction.setComparisonFunction(comparisonOperator);
                } catch (IllegalArgumentException e) {
                    throw new ParseException(e.getMessage());
                }
                return dateFunction;
            }
//            if (Function.class.isInstance(comparisonOperator.getLeftExpression())) {
//                Function function = ((Function) comparisonOperator.getLeftExpression());
//                if ("date".equals(function.getName().toLowerCase())
////                        && (function.getParameters().getExpressions().size() == 2)
////                        && StringValue.class.isInstance(function.getParameters().getExpressions().get(1))
//                        ) {
//                    String column = getStringValue(function.getParameters().getExpressions().get(0));
//                    DateFunction dateFunction = null;
//                    try {
//                        String format = "natural";
//                        if (function.getParameters().getExpressions().size() == 2)
//                            format = ((StringValue) (function.getParameters().getExpressions().get(1))).getValue();
//                        dateFunction = new DateFunction(format, rightExpression, column);
//                        dateFunction.setComparisonFunction(comparisonOperator);
//                    } catch (IllegalArgumentException e) {
//                        throw new ParseException(e.getMessage());
//                    }
//                    return dateFunction;
//                }
//
//            }
        }
        return null;
    }

    public static RegexFunction isRegexFunction(Expression incomingExpression) throws ParseException {
        if (EqualsTo.class.isInstance(incomingExpression)) {
            EqualsTo equalsTo = (EqualsTo) incomingExpression;
            String rightExpression = equalsTo.getRightExpression().toString();
            if (Function.class.isInstance(equalsTo.getLeftExpression())) {
                Function function = ((Function) equalsTo.getLeftExpression());
                if (REGEXMATCH_FUNCTION.equalsIgnoreCase(function.getName())
                        && (function.getParameters().getExpressions().size() == 2
                        || function.getParameters().getExpressions().size() == 3)
                        && StringValue.class.isInstance(function.getParameters().getExpressions().get(1))) {

                    final Boolean rightExpressionValue = Boolean.valueOf(rightExpression);

                    isTrue(rightExpressionValue, "false is not allowed for regexMatch function");

                    RegexFunction regexFunction = getRegexFunction(function);
                    return regexFunction;
                }

            }
        } else if (Function.class.isInstance(incomingExpression)) {
            Function function = ((Function) incomingExpression);
            if (REGEXMATCH_FUNCTION.equalsIgnoreCase(function.getName())
                    && (function.getParameters().getExpressions().size() == 2
                    || function.getParameters().getExpressions().size() == 3)
                    && StringValue.class.isInstance(function.getParameters().getExpressions().get(1))) {

                RegexFunction regexFunction = getRegexFunction(function);
                return regexFunction;

            }
        }
        return null;
    }

    private static RegexFunction getRegexFunction(Function function) throws ParseException {
        final String column = getStringValue(function.getParameters().getExpressions().get(0));
        final String regex = fixDoubleSingleQuotes(
                ((StringValue) (function.getParameters().getExpressions().get(1))).getValue());
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ParseException(e.getMessage());
        }
        RegexFunction regexFunction = new RegexFunction(column, regex);

        if (function.getParameters().getExpressions().size() == 3 && StringValue.class
                .isInstance(function.getParameters().getExpressions().get(2))) {
            regexFunction.setOptions(
                    ((StringValue) (function.getParameters().getExpressions().get(2))).getValue());
        }
        return regexFunction;
    }

    public static void isTrue(boolean expression, String message) throws ParseException {
        if (!expression) {
            throw new ParseException(message);
        }
    }

    public static void isFalse(boolean expression, String message) throws ParseException {
        if (expression) {
            throw new ParseException(message);
        }
    }

}
