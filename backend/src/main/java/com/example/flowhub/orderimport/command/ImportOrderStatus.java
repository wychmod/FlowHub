package com.example.flowhub.orderimport.command;

/** 订单状态白名单（行级校验用，大小写不敏感经 ParamUtils.enumFromName 解析）。 */
public enum ImportOrderStatus {
    PENDING, PAID, SHIPPED, COMPLETED, CANCELED
}