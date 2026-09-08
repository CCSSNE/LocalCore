package com.localcore.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Validates user-defined hot-setting controls and their parameter mappings. */
public final class HotSettings {
    public static final String CONFIG_KEY = "hotSettings";
    private static final Set<String> DEFAULT_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "max_tokens", "maxTokens", "temperature", "top_p", "topP", "top_k", "topK", "seed", "stop")));

    private HotSettings() {
    }

    public static JSONObject parse(String text) {
        if (text == null) throw new IllegalArgumentException("hotSettings definition cannot be null");
        try {
            JSONObject value = new JSONObject(text);
            validate(value);
            return value;
        } catch (JSONException error) {
            throw new IllegalArgumentException("hotSettings definition is not valid JSON: " + error.getMessage(), error);
        }
    }

    public static void validateConfig(JSONObject config) {
        if (!config.has(CONFIG_KEY)) return;
        Object value = config.opt(CONFIG_KEY);
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException("config.hotSettings must be a control definition object");
        }
        validate((JSONObject) value);
    }

    public static void validate(JSONObject value) {
        requireExactKeys(value, "hotSettings", "controls");
        JSONArray controls = requiredArray(value, "controls", "hotSettings");
        Set<String> ids = new HashSet<>();
        Set<String> mappedKeys = new HashSet<>();
        for (int i = 0; i < controls.length(); i++) {
            JSONObject control = objectAt(controls, i, "controls[" + i + "]");
            validateControl(control, i, ids, mappedKeys);
        }
    }

    public static JSONObject fromConfig(JSONObject config) {
        if (!config.has(CONFIG_KEY)) return empty();
        Object value = config.opt(CONFIG_KEY);
        if (!(value instanceof JSONObject)) {
            throw new IllegalStateException("config.hotSettings must be a control definition object");
        }
        JSONObject result = (JSONObject) value;
        validate(result);
        return result;
    }

    private static void validateControl(JSONObject control, int index, Set<String> ids,
                                        Set<String> mappedKeys) {
        String path = "controls[" + index + "]";
        String type = requiredString(control, "type", path);
        if ("toggle".equals(type)) {
            requireExactKeys(control, path, "id", "label", "type", "default", "states");
            String id = requiredId(control, path, ids);
            requiredString(control, "label", path);
            String defaultState = requiredString(control, "default", path);
            JSONObject states = requiredObject(control, "states", path);
            requireExactKeys(states, path + ".states", "on", "off");
            Set<String> localKeys = new HashSet<>();
            Set<String> onKeys = new HashSet<>();
            Set<String> offKeys = new HashSet<>();
            validateState(states, "on", path, onKeys);
            validateState(states, "off", path, offKeys);
            localKeys.addAll(onKeys);
            localKeys.addAll(offKeys);
            mergeMappedKeys(localKeys, mappedKeys, path);
            if (!"on".equals(defaultState) && !"off".equals(defaultState)) {
                throw new IllegalArgumentException(path + ".default must be on or off: " + id);
            }
            return;
        }
        if ("select".equals(type)) {
            requireExactKeys(control, path, "id", "label", "type", "default", "options");
            String id = requiredId(control, path, ids);
            requiredString(control, "label", path);
            String defaultValue = requiredString(control, "default", path);
            JSONArray options = requiredArray(control, "options", path);
            if (options.length() == 0) throw new IllegalArgumentException(path + ".options cannot be empty");
            Set<String> values = new HashSet<>();
            Set<String> localKeys = new HashSet<>();
            boolean foundDefault = false;
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = objectAt(options, i, path + ".options[" + i + "]");
                String optionPath = path + ".options[" + i + "]";
                requireExactKeys(option, optionPath, "value", "label", "params");
                String optionValue = requiredString(option, "value", optionPath);
                requiredString(option, "label", optionPath);
                if (!values.add(optionValue)) {
                    throw new IllegalArgumentException(optionPath + ".value is duplicated: " + optionValue);
                }
                Set<String> optionKeys = new HashSet<>();
                validateParams(requiredObject(option, "params", optionPath), optionPath + ".params", optionKeys);
                localKeys.addAll(optionKeys);
                if (defaultValue.equals(optionValue)) foundDefault = true;
            }
            if (!foundDefault) throw new IllegalArgumentException(path + ".default is not present in options: " + id);
            mergeMappedKeys(localKeys, mappedKeys, path);
            return;
        }
        if ("input".equals(type)) {
            requireExactKeys(control, path, "id", "label", "type", "valueType", "default", "bind");
            String id = requiredId(control, path, ids);
            requiredString(control, "label", path);
            String valueType = requiredString(control, "valueType", path);
            Object defaultValue = control.opt("default");
            if ("number".equals(valueType)) {
                if (!(defaultValue instanceof Number) || !Double.isFinite(((Number) defaultValue).doubleValue())) {
                    throw new IllegalArgumentException(path + ".default must be a finite number");
                }
            } else if ("text".equals(valueType)) {
                if (!(defaultValue instanceof String)) {
                    throw new IllegalArgumentException(path + ".default must be a string");
                }
            } else {
                throw new IllegalArgumentException(path + ".valueType must be number or text: " + id);
            }
            JSONObject bind = requiredObject(control, "bind", path);
            if (bind.length() == 0) throw new IllegalArgumentException(path + ".bind cannot be empty");
            Set<String> localKeys = new HashSet<>();
            Iterator<String> keys = bind.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (DEFAULT_KEYS.contains(key)) {
                    throw new IllegalArgumentException("custom control cannot map default parameter: " + key);
                }
                if (!localKeys.add(key)) {
                    throw new IllegalArgumentException(path + ".bind has duplicate mapping: " + key);
                }
                if (!"$value".equals(bind.optString(key, ""))) {
                    throw new IllegalArgumentException(path + ".bind[" + key + "] must be $value");
                }
            }
            mergeMappedKeys(localKeys, mappedKeys, path);
            return;
        }
        throw new IllegalArgumentException(path + ".type is unsupported: " + type);
    }

    private static void validateState(JSONObject states, String name, String path, Set<String> mappedKeys) {
        JSONObject state = requiredObject(states, name, path + ".states");
        String statePath = path + ".states." + name;
        requireExactKeys(state, statePath, "label", "params");
        requiredString(state, "label", statePath);
        validateParams(requiredObject(state, "params", statePath), statePath + ".params", mappedKeys);
    }

    private static void validateParams(JSONObject params, String path, Set<String> mappedKeys) {
        Iterator<String> keys = params.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.isEmpty()) throw new IllegalArgumentException(path + " contains an empty parameter name");
            if (DEFAULT_KEYS.contains(key)) {
                throw new IllegalArgumentException("custom control cannot map default parameter: " + key);
            }
            if (!mappedKeys.add(key)) {
                throw new IllegalArgumentException(path + " maps the same parameter more than once: " + key);
            }
        }
    }

    private static void mergeMappedKeys(Set<String> localKeys, Set<String> mappedKeys, String path) {
        for (String key : localKeys) {
            if (!mappedKeys.add(key)) {
                throw new IllegalArgumentException(path + " duplicates mapping from another control: " + key);
            }
        }
    }

    private static String requiredId(JSONObject object, String path, Set<String> ids) {
        String id = requiredString(object, "id", path);
        if (!ids.add(id)) throw new IllegalArgumentException(path + ".id is duplicated: " + id);
        return id;
    }

    private static String requiredString(JSONObject object, String key, String path) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException(path + "." + key + " must be a non-empty string");
        }
        return (String) value;
    }

    private static JSONObject requiredObject(JSONObject object, String key, String path) {
        Object value = object.opt(key);
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException(path + "." + key + " must be an object");
        return (JSONObject) value;
    }

    private static JSONArray requiredArray(JSONObject object, String key, String path) {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) throw new IllegalArgumentException(path + "." + key + " must be an array");
        return (JSONArray) value;
    }

    private static JSONObject objectAt(JSONArray array, int index, String path) {
        Object value = array.opt(index);
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException(path + " must be an object");
        return (JSONObject) value;
    }

    private static void requireExactKeys(JSONObject object, String path, String... allowed) {
        Set<String> expected = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!expected.contains(key)) throw new IllegalArgumentException(path + " contains unsupported field: " + key);
        }
        for (String key : allowed) {
            if (!object.has(key)) throw new IllegalArgumentException(path + " is missing field: " + key);
        }
    }

    private static JSONObject empty() {
        try {
            return new JSONObject().put("controls", new JSONArray());
        } catch (JSONException error) {
            throw new IllegalStateException("cannot create empty hotSettings definition", error);
        }
    }
}
