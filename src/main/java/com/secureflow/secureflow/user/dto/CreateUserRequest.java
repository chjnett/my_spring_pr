package com.secureflow.secureflow.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;


//Entity는 데이터베이스와 매핑되는 객체,DTO(API 응답에 필요한 데이터만 DTO로 변환 계층간 데이터를 전달
//record는 주로 데이터를 보관하기 위해 사용하는 클래스 선언 방식

public record CreateUserRequest (

        //빈 문자열,공백만 있는 문자열을 허용하지 않음
        @NotBlank
        String name,

        @NotBlank
        @Email
        String email


){}