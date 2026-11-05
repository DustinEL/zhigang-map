package com.zhigang.platform.emerging;

public class DiscoveryExecutionException extends RuntimeException {
    private final String code;

    public DiscoveryExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DiscoveryExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
