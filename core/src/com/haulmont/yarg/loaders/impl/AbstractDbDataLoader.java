package com.haulmont.yarg.loaders.impl;

import com.haulmont.yarg.exception.DataLoadingException;
import com.haulmont.yarg.structure.BandData;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author degtyarjov
 * @version $Id: AbstractDbDataLoader.java 9328 2012-10-18 15:28:32Z artamonov $
 */
public abstract class AbstractDbDataLoader extends AbstractDataLoader {
    protected void addParentBandDataToParameters(BandData parentBand, Map<String, Object> currentParams) {
        if (parentBand != null) {
            String parentBandName = parentBand.getName();

            for (Map.Entry<String, Object> entry : parentBand.getData().entrySet()) {
                currentParams.put(parentBandName + "." + entry.getKey(), entry.getValue());
            }
        }
    }

    protected List<Map<String, Object>> fillOutputData(List resList, List<String> parametersNames) {
        List<Map<String, Object>> outputData = new ArrayList<Map<String, Object>>();

        for (Object resultRecordObject : resList) {
            Map<String, Object> outputParameters = new HashMap<String, Object>();
            if (resultRecordObject instanceof Object[]) {
                Object[] resultRecord = (Object[]) resultRecordObject;

                if (resultRecord.length != parametersNames.size()) {
                    throw new DataLoadingException(String.format("Result set size [%d] does not match output fields count [%s]. Detected output fields %s", resultRecord.length, parametersNames.size(), parametersNames));
                }

                for (Integer i = 0; i < resultRecord.length; i++) {
                    outputParameters.put(parametersNames.get(i), resultRecord[i]);
                }
            } else {
                outputParameters.put(parametersNames.get(0), resultRecordObject);
            }
            outputData.add(outputParameters);
        }
        return outputData;
    }

    protected QueryPack prepareQuery(String query, BandData parentBand, Map<String, Object> reportParams) {
        Map<String, Object> currentParams = new HashMap<String, Object>();
        if (reportParams != null) {
            currentParams.putAll(reportParams);
        }

        //adds parameters from parent bands hierarchy
        while (parentBand != null) {
            addParentBandDataToParameters(parentBand, currentParams);
            parentBand = parentBand.getParentBand();
        }

        List<QueryParameter> queryParameters = new ArrayList<QueryParameter>();
        List<Object> values = new ArrayList<Object>();

        int paramCount = 1;
        for (Map.Entry<String, Object> entry : currentParams.entrySet()) {
            // Remembers ${alias} positions
            String paramName = entry.getKey();
            Object paramValue = entry.getValue();

            String alias = "${" + paramName + "}";
            String paramNameRegexp = "\\$\\{" + paramName + "\\}";
            String deleteRegexp = "(?i)\\s*(and|or)?\\s+[\\w|\\d|\\.|\\_]+\\s+(=|>=|<=|like|>|<)\\s*" + paramNameRegexp;

            //if value == null - removing condition from query
            if (paramValue == null) {
                // remove unused null parameter
                query = query.replaceAll(deleteRegexp, "");
            } else if (query.contains(alias)) {
                Pattern pattern = Pattern.compile(paramNameRegexp);
                Matcher matcher = pattern.matcher(query);

                int subPosition = 0;
                while (matcher.find(subPosition)) {
                    queryParameters.add(new QueryParameter(paramNameRegexp, paramCount++, convertParameter(paramValue)));
                    subPosition = matcher.end();
                }
            }
        }

        // Sort params by position
        Collections.sort(queryParameters, new Comparator<QueryParameter>() {
            @Override
            public int compare(QueryParameter o1, QueryParameter o2) {
                return o1.getPosition().compareTo(o2.getPosition());
            }
        });

        for (QueryParameter parameter : queryParameters) {
            query = insertParameterToQuery(query, parameter);
        }

        query = query.trim();
        if (query.endsWith("where")) {
            query = query.replace("where", "");
        }

        return new QueryPack(query, queryParameters.toArray(new QueryParameter[queryParameters.size()]));
    }

    protected String insertParameterToQuery(String query, QueryParameter parameter) {
        if (parameter.isSingleValue()) {
            // Replace single parameter with ?
            query = query.replaceAll(parameter.getParamRegexp(), "?");
        } else {
            // Replace multiple parameter with ?,..(N)..,?
            List<?> multipleValues = parameter.getMultipleValues();
            StringBuilder builder = new StringBuilder();
            for (Object value : multipleValues) {
                builder.append("?,");
            }
            builder.deleteCharAt(builder.length() - 1);
            query = query.replaceAll(parameter.getParamRegexp(), builder.toString());
        }
        return query;
    }

    protected static class QueryPack {
        private String query;
        private QueryParameter[] params;

        public QueryPack(String query, QueryParameter[] params) {
            this.query = query;
            this.params = params;
        }

        public String getQuery() {
            return query;
        }

        public QueryParameter[] getParams() {
            return params;
        }
    }

    protected static class QueryParameter {
        private Integer position;
        private Object value;
        private String paramRegexp;

        public QueryParameter(String paramRegexp, Integer position, Object value) {
            this.position = position;
            this.value = value;
            this.paramRegexp = paramRegexp;
        }

        public Integer getPosition() {
            return position;
        }

        public Object getValue() {
            return value;
        }

        public String getParamRegexp() {
            return paramRegexp;
        }

        public boolean isSingleValue() {
            return !(value instanceof Collection || value instanceof Object[]);
        }

        public List<?> getMultipleValues() {
            if (isSingleValue()) {
                return Collections.singletonList(value);
            } else {
                if (value instanceof Collection) {
                    return new ArrayList<Object>((Collection<?>) value);
                } else if (value instanceof Object[]) {
                    return Arrays.asList((Object[]) value);
                }
            }

            return null;
        }
    }
}