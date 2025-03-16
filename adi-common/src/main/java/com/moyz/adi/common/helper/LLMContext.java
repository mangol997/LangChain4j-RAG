package com.moyz.adi.common.helper;

import com.moyz.adi.common.entity.AiModel;
import com.moyz.adi.common.enums.ErrorEnum;
import com.moyz.adi.common.exception.BaseException;
import com.moyz.adi.common.interfaces.AbstractLLMService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * llmService上下文类（策略模式）
 */
@Slf4j
public class LLMContext {
    protected static final Map<String, AbstractLLMService<?>> NAME_TO_LLM_SERVICE = new LinkedHashMap<>();

    private LLMContext() {
    }

    public static void addLLMService(AbstractLLMService<?> llmService) {
        NAME_TO_LLM_SERVICE.put(llmService.getAiModel().getName(), llmService);
    }

    /**
     * 清除{modelPlatform}下的缓存
     *
     * @param modelPlatform 模型所属的平台
     */
    public static void clearByPlatform(String modelPlatform) {
        List<String> needDeleted = NAME_TO_LLM_SERVICE.values()
                .stream()
                .filter(item -> item.getAiModel().getPlatform().equalsIgnoreCase(modelPlatform))
                .map(item -> item.getAiModel().getName())
                .toList();
        for (String key : needDeleted) {
            log.info("delete llm model service,modelName:{}", key);
            NAME_TO_LLM_SERVICE.remove(key);
        }
    }

    public static void remove(String modelName) {
        NAME_TO_LLM_SERVICE.remove(modelName);
    }

    public static AiModel getAiModel(String modelName) {
        return NAME_TO_LLM_SERVICE.get(modelName).getAiModel();
    }

    public static Map<String, AbstractLLMService<?>> getAllServices() {
        return NAME_TO_LLM_SERVICE;
    }

    public static AbstractLLMService<?> getLLMServiceByName(String modelName) {
        AbstractLLMService<?> service = NAME_TO_LLM_SERVICE.get(modelName);
        if (null == service) {
            Optional<AbstractLLMService<?>> serviceOptional = getFirstEnableAndFree();
            if (serviceOptional.isPresent()) {
                log.warn("︿︿︿ 找不到 {},使用第1个可用的免费模型 {} ︿︿︿", modelName, serviceOptional.get().getAiModel().getName());
                return serviceOptional.get();
            }
            log.error("︿︿︿ 没有可用的模型,请检查平台及模型的配置 ︿︿︿");
            throw new BaseException(ErrorEnum.A_ENABLE_MODEL_NOT_FOUND);
        }
        return service;
    }

    public static AbstractLLMService<?> getLLMServiceById(Long modelId) {
        AiModel aiModel = NAME_TO_LLM_SERVICE.values().stream()
                .map(AbstractLLMService::getAiModel)
                .filter(item -> item.getId().equals(modelId))
                .findFirst().orElse(null);
        return LLMContext.getLLMServiceByName(null == aiModel ? "" : aiModel.getName());
    }

    /**
     * 选择顺序：
     * 一、优先选择免费可用的模型；
     * 二、收费可用的模型
     *
     * @return 返回免费可用或收费可用的模型
     */
    public static Optional<AbstractLLMService<?>> getFirstEnableAndFree() {
        return NAME_TO_LLM_SERVICE.values().stream().filter(item -> {
            AiModel aiModel = item.getAiModel();
            if (aiModel.getIsEnable() && aiModel.getIsFree()) {
                return true;
            } else return Boolean.TRUE.equals(aiModel.getIsEnable());
        }).findFirst();
    }
}
