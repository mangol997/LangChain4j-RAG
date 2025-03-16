package com.moyz.adi.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

@Validated
@Data
public class QAReq {

    @NotBlank
    private String qaRecordUuid;
    
    @NotBlank
    private String modelName;
}
