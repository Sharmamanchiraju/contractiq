package com.contractiq.common.security;

/**
 * Facts about the HTTP request being handled, for the audit log and for log lines.
 *
 * @param requestId a fresh random id made by the server for this request (never taken from the client)
 * @param ipAddress the address of the direct network connection. Behind a proxy or load balancer this
 *                  is the proxy's address, because forwarded-for headers are not trusted by default.
 */
public record RequestInfo(String requestId, String ipAddress) {
}
