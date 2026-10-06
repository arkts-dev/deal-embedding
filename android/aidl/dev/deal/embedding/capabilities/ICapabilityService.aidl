package dev.deal.embedding.capabilities;
import dev.deal.embedding.capabilities.ICapabilityResult;
interface ICapabilityService {
    String describe();
    void invoke(String requestId, String functionName, String argumentsJson, ICapabilityResult callback);
    void cancel(String requestId);
}
