package com.ok.member.member.service.dto;

public record SignupCommand(
        String loginId,
        String password,
        String email,
        String name,
        String nickname
) {
}
