package org.json;

public class JSONObject {
    public JSONObject() {}
    public JSONObject(String json) {}
    public JSONObject put(String name, Object value) throws Exception { return this; }
    public JSONObject put(String name, double value) throws Exception { return this; }
    public JSONObject put(String name, int value) throws Exception { return this; }
    public JSONArray optJSONArray(String name) { return null; }
    public JSONObject optJSONObject(String name) { return null; }
    public String optString(String name, String fallback) { return ""; }
}
