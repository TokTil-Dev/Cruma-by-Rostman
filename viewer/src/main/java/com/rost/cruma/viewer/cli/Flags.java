package com.rost.cruma.viewer.cli;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Flags {
    private final Map<String, FlagValue> flagValuesByNames;

    private Flags(Map<String, FlagValue> flagValuesByNames) {
        this.flagValuesByNames = flagValuesByNames;
    }

    private record FlagValue(Object value) {}

    public static Flags parse(String[] args, List<Flag<?>> flags) {
        var flagValuesByNames = new HashMap<String, FlagValue>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--")) {
                var name = arg.substring(2);
                var flag = findFlagByLongName(name, flags);
                i = processFlagValue(args, flagValuesByNames, i, name, flag);
            } else if (arg.startsWith("-")) {
                var name = arg.substring(1);
                var flag = findFlagByShortName(name, flags);
                i = processFlagValue(args, flagValuesByNames, i, name, flag);
            }
        }
        for (var flag : flags) {
            if (!flagValuesByNames.containsKey(flag.getLongName())) {
                if (flag instanceof BooleanFlag) {
                    flagValuesByNames.put(flag.getLongName(), new FlagValue(false));
                } else if (flag.getDefaultValue() != null) {
                    flagValuesByNames.put(
                            flag.getLongName(),
                            new FlagValue(flag.getDefaultValue().orElse(null)));
                }
            }
        }
        return new Flags(flagValuesByNames);
    }

    public <T> T getValue(Flag<T> flag) {
        return getValue(flag.getLongName());
    }

    @SuppressWarnings("unchecked")
    public <T> T getValue(String name) {
        var flagValue = flagValuesByNames.get(name);
        if (flagValue == null) {
            throw new IllegalArgumentException("Flag '" + name + "' was not set");
        }
        return (T) flagValue.value;
    }

    private static int processFlagValue(
            String[] args, Map<String, FlagValue> flagsByNames, int i, String name, Flag<?> flag) {
        if (flag == null) {
            throw new IllegalArgumentException("Unknown flag: " + name);
        }
        if (i + 1 == args.length || args[i + 1].startsWith("-")) {
            if (flag.getDefaultValue() == null || flag.getDefaultValue().isEmpty()) {
                if (flag instanceof BooleanFlag) {
                    flagsByNames.put(flag.getLongName(), new FlagValue(true));
                    return i;
                }
                throw new IllegalArgumentException("Flag " + name + " requires a value");
            }
            flagsByNames.put(
                    flag.getLongName(), new FlagValue(flag.getDefaultValue().get()));
            return i;
        }
        var value = flag.parse(args[++i]);
        flagsByNames.put(flag.getLongName(), new FlagValue(value));
        return i;
    }

    private static Flag<?> findFlagByShortName(String shortName, List<Flag<?>> flags) {
        for (var flag : flags) {
            if (flag.getShortName().equals(shortName)) {
                return flag;
            }
        }
        return null;
    }

    private static Flag<?> findFlagByLongName(String longName, List<Flag<?>> flags) {
        for (var flag : flags) {
            if (flag.getLongName().equals(longName)) {
                return flag;
            }
        }
        return null;
    }
}
