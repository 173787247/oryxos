package io.oryxos.core.a2a;

/** JSON-RPC 2.0 error object for A2A responses. */
public record A2aJsonRpcError(int code, String message) {
  public static final int PARSE_ERROR = -32700;
  public static final int INVALID_REQUEST = -32600;
  public static final int METHOD_NOT_FOUND = -32601;
  public static final int INVALID_PARAMS = -32602;
  public static final int INTERNAL_ERROR = -32603;

  public A2aJsonRpcError {
    message = message == null ? "" : message;
  }
}
