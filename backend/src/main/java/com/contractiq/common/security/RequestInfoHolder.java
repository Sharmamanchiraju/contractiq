package com.contractiq.common.security;

/**
 * Holds the {@link RequestInfo} of the request running on this thread. Set by
 * {@link RequestInfoFilter} and always cleared when the request ends. Code that runs outside a web
 * request (such as the analysis job) sees nothing here.
 */
public final class RequestInfoHolder {

    private static final ThreadLocal<RequestInfo> CURRENT = new ThreadLocal<>();

    private RequestInfoHolder() {
    }

    static void set(RequestInfo info) {
        CURRENT.set(info);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** The current request's info, or null when not inside a web request. */
    public static RequestInfo current() {
        return CURRENT.get();
    }
}
