package com.merchanthub.common.common;

/**
 * Custom error codes
 *
 * Note: messages for the 91xxx/92xxx range come from the ValidationError enum (single source of truth)
 */
public enum ErrorCode {
    // ============ General errors (40xxx) ============
    PARAMS_ERROR(40000, "请求参数错误"),
    NOT_LOGIN_ERROR(40100, "未登录"),
    NO_AUTH_ERROR(40101, "无权限"),
    TOKEN_MISSING(40102, "令牌缺失"),
    TOKEN_EXPIRED(40103, "令牌已过期"),
    TOKEN_INVALID(40104, "令牌无效"),
    TOKEN_MISMATCH(40105, "令牌与用户不匹配"),
    FORBIDDEN_ERROR(40300, "禁止访问"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),
    // C2: rate limited (HTTP 429). The error code mirrors the HTTP status so clients retry later instead of treating it as "order failed"
    TOO_MANY_REQUESTS(42900, "当前商家下单人数过多，请稍后重试"),
    SENSITIVE_WORD_ERROR(40003, "包含敏感词，请求被拒绝"),

    // ============ System errors (50xxx) ============
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败"),
    INVALID_PARAMETER_ERROR(50003, "参数校验失败"),
    PLEASE_LOGIN(50004, "请先登录"),
    SAME_LOGIN_CONFLICT(50005, "账号已在其他地方登录"),
    SYSTEM_BUSY(50008, "系统繁忙，请稍后重试"),

    // ============ Trading: product / order (60xxx), inventory (61xxx) (B1) ============
    SKU_NOT_FOUND(60001, "商品不存在"),
    SKU_NOT_ON_SALE(60002, "商品已下架或停售"),
    ORDER_MULTI_TENANT(60003, "一个订单只能包含同一商家的商品"),
    PRICE_CHANGED(60004, "商品价格已变动，请确认后重新下单"),
    DOWNSTREAM_UNAVAILABLE(60005, "依赖服务暂不可用，请稍后重试"),
    INVENTORY_INSUFFICIENT(61001, "库存不足"),
    RESERVATION_CONFLICT(61002, "同一订单号的预占内容不一致"),
    ORDER_NOT_PAYABLE(60006, "订单当前不是待支付状态"),
    SKU_TENANT_MISMATCH(60007, "商品不属于该商家"),
    // D1: only draft / rejected products can be submitted for listing review (resubmitting one under review or already listed is a duplicate request)
    PRODUCT_NOT_SUBMITTABLE(60008, "商品当前状态不能提交上架审核"),
    // ============ Payment (62xxx) (B3) ============
    PAYMENT_NOT_FOUND(62001, "支付单不存在"),
    PAY_AMOUNT_MISMATCH(62002, "回调金额与支付单金额不一致"),
    CHANNEL_TRADE_NO_CONFLICT(62003, "渠道流水号已被其他支付单使用"),

    // ============ Approval (63xxx) (D1) ============
    // Not "no permission": another merchant's task and a nonexistent task return the same result, so we don't leak "this ID exists" (same as C1 returning 404 for cross-tenant order lookups)
    APPROVAL_TASK_NOT_FOUND(63001, "待办不存在"),
    APPROVAL_TASK_CONFLICT(63002, "该待办已被处理，请刷新"),
    APPLICATION_STATE_INVALID(63003, "申请当前状态不允许此操作"),
    USERNAME_TAKEN(63004, "登录名已被占用"),
    APPLICATION_IN_PROGRESS(63005, "已有进行中的入驻申请"),

    // ============ User errors (70xxx) ============
    PHONE_EMAIL_ERROR(70000, "手机号/邮箱格式错误"),
    USER_ALREADY_EXISTS(70001, "用户已存在"),
    USER_NOT_EXISTS(70002, "用户不存在"),
    REGISTER_ERROR(70003, "注册失败"),
    LOGIN_ERROR_CODE(70004, "验证码错误"),
    LOGIN_ERROR(70005, "登录失败, 用户名或密码错误"),
    LoginPasswordError(70006, "两次密码不一致"),

    // ============ WebSocket parameter errors (90xxx) ============
    SIGNAL_TYPE_ERROR(90000, "单聊消息必须指定接收者"),
    GROUP_TYPE_ERROR(90001, "群聊消息不需要指定接收者"),
    INVALID_TOKEN(90003, "无效token，请重新登录"),
    USER_EMAIL_LIST_EMPTY(90004, "用户邮箱列表为空，请检查用户服务是否正常或没有用户注册");

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