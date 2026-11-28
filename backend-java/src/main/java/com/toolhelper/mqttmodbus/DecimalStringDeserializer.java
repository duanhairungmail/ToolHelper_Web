package com.toolhelper.mqttmodbus;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 仅约束电表测量值，防止 JSON 数字被自动转成字符串后掩盖调用方的浮点误差。 */
public final class DecimalStringDeserializer extends ValueDeserializer<String> {
    @Override public String deserialize(JsonParser parser,DeserializationContext context) {
        if(parser.currentToken()!=JsonToken.VALUE_STRING)
            return context.reportInputMismatch(String.class,"电表测量值必须为十进制字符串");
        return parser.getString();
    }
}
