package dev.deal.embedding.capabilities;
oneway interface ICapabilityResult {
    void success(String requestId, String valueJson);
    void failure(String requestId, String code, String message);
}
