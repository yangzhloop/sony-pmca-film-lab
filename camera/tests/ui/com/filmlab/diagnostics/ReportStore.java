package com.filmlab.diagnostics;
public final class ReportStore {
    public static class ExportException extends java.io.IOException {
        private static final long serialVersionUID = 1L;
        public ExportException(String message) { super(message); }
        public String getCode() { return getMessage(); }
    }
}
