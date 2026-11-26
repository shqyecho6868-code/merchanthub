package com.merchanthub.common.common;

/**
 * Custom error codes
 *
 * Note: messages for the 91xxx/92xxx range come from the ValidationError enum (single source of truth)
 */
public enum ErrorCode {
    // ============ General errors (40xxx) ============
    PARAMS_ERROR(40000, "Invalid request parameters"),
    NOT_LOGIN_ERROR(40100, "Not logged in"),
    NO_AUTH_ERROR(40101, "No permission"),
    TOKEN_MISSING(40102, "Token missing"),
    TOKEN_EXPIRED(40103, "Token expired"),
    TOKEN_INVALID(40104, "Invalid token"),
    TOKEN_MISMATCH(40105, "Token does not match the user"),
    FORBIDDEN_ERROR(40300, "Access forbidden"),
    NOT_FOUND_ERROR(40400, "Requested data not found"),
    // C2: rate limited (HTTP 429). The error code mirrors the HTTP status so clients retry later instead of treating it as "order failed"
    TOO_MANY_REQUESTS(42900, "Too many orders for this merchant right now, please try again later"),
    SENSITIVE_WORD_ERROR(40003, "Request rejected: contains sensitive words"),

    // ============ System errors (50xxx) ============
    SYSTEM_ERROR(50000, "Internal system error"),
    OPERATION_ERROR(50001, "Operation failed"),
    INVALID_PARAMETER_ERROR(50003, "Parameter validation failed"),
    PLEASE_LOGIN(50004, "Please log in first"),
    SAME_LOGIN_CONFLICT(50005, "Account is logged in elsewhere"),
    SYSTEM_BUSY(50008, "System busy, please try again later"),

    // ============ Trading: product / order (60xxx), inventory (61xxx) (B1) ============
    SKU_NOT_FOUND(60001, "Product not found"),
    SKU_NOT_ON_SALE(60002, "Product is delisted or not on sale"),
    ORDER_MULTI_TENANT(60003, "An order may only contain products from a single merchant"),
    PRICE_CHANGED(60004, "Product price has changed, please confirm and place the order again"),
    DOWNSTREAM_UNAVAILABLE(60005, "A dependent service is temporarily unavailable, please try again later"),
    INVENTORY_INSUFFICIENT(61001, "Insufficient inventory"),
    RESERVATION_CONFLICT(61002, "Reservation content differs for the same order number"),
    ORDER_NOT_PAYABLE(60006, "Order is not awaiting payment"),
    SKU_TENANT_MISMATCH(60007, "Product does not belong to this merchant"),
    // D1: only draft / rejected products can be submitted for listing review (resubmitting one under review or already listed is a duplicate request)
    PRODUCT_NOT_SUBMITTABLE(60008, "Product cannot be submitted for listing review in its current status"),
    // ============ Payment (62xxx) (B3) ============
    PAYMENT_NOT_FOUND(62001, "Payment not found"),
    PAY_AMOUNT_MISMATCH(62002, "Callback amount does not match the payment amount"),
    CHANNEL_TRADE_NO_CONFLICT(62003, "Channel trade number is already used by another payment"),

    // ============ Approval (63xxx) (D1) ============
    // Not "no permission": another merchant's task and a nonexistent task return the same result, so we don't leak "this ID exists" (same as C1 returning 404 for cross-tenant order lookups)
    APPROVAL_TASK_NOT_FOUND(63001, "Task not found"),
    APPROVAL_TASK_CONFLICT(63002, "This task has already been handled, please refresh"),
    APPLICATION_STATE_INVALID(63003, "Operation not allowed in the application's current status"),
    USERNAME_TAKEN(63004, "Login name is already taken"),
    APPLICATION_IN_PROGRESS(63005, "An onboarding application is already in progress"),

    // ============ User errors (70xxx) ============
    PHONE_EMAIL_ERROR(70000, "Invalid phone number/email format"),
    USER_ALREADY_EXISTS(70001, "User already exists"),
    USER_NOT_EXISTS(70002, "User does not exist"),
    REGISTER_ERROR(70003, "Registration failed"),
    LOGIN_ERROR_CODE(70004, "Invalid verification code"),
    LOGIN_ERROR(70005, "Login failed: wrong username or password"),
    LoginPasswordError(70006, "Passwords do not match"),

    // ============ WebSocket parameter errors (90xxx) ============
    SIGNAL_TYPE_ERROR(90000, "A one-to-one message must specify a receiver"),
    GROUP_TYPE_ERROR(90001, "A group message must not specify a receiver"),
    INVALID_TOKEN(90003, "Invalid token, please log in again"),
    USER_EMAIL_LIST_EMPTY(90004, "User email list is empty; check that the user service is healthy or that any users are registered");

    /**
     * Status code
     */
    private final int code;

    /**
     * Message
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

}