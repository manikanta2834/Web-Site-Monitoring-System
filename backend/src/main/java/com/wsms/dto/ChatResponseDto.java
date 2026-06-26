package com.wsms.dto;

import java.util.List;
import java.util.Map;

public class ChatResponseDto {
    private String response;
    private String generatedSql;
    private List<Map<String, Object>> queryResult;

    public ChatResponseDto() {}

    public ChatResponseDto(String response, String generatedSql, List<Map<String, Object>> queryResult) {
        this.response = response;
        this.generatedSql = generatedSql;
        this.queryResult = queryResult;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getGeneratedSql() {
        return generatedSql;
    }

    public void setGeneratedSql(String generatedSql) {
        this.generatedSql = generatedSql;
    }

    public List<Map<String, Object>> getQueryResult() {
        return queryResult;
    }

    public void setQueryResult(List<Map<String, Object>> queryResult) {
        this.queryResult = queryResult;
    }
}
