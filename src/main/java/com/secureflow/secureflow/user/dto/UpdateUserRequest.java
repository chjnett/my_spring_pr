package com.secureflow.secureflow.user.dto;

import jakarta.validation.constraints.NotBlank;


//DTO->id를 제외하고 name만 api로 반환
public record UpdateUserRequest (
        @NotBlank
        String name

){

}
