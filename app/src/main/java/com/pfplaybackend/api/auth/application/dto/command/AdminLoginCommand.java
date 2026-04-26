package com.pfplaybackend.api.auth.application.dto.command;

public record AdminLoginCommand(String email, String password, String clientIp) {}
