package com.rost.cruma.core.area;

import com.rost.cruma.core.spawn.SourceSpan;
import java.util.Objects;

public record AreaSetting(String key, String value, SourceSpan span, SourceSpan valueSpan) {
    public AreaSetting {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(valueSpan, "valueSpan");
    }

    public static AreaSetting of(String key, String value) {
        return new AreaSetting(key, value, SourceSpan.NONE, SourceSpan.NONE);
    }

    public AreaSetting withValue(String newValue) {
        return new AreaSetting(key, newValue, span, valueSpan);
    }

    public AreaSetting detached() {
        return of(key, value);
    }

    public boolean isInFile() {
        return span.isPresent();
    }

    public String format() {
        return key + "=" + value;
    }
}
