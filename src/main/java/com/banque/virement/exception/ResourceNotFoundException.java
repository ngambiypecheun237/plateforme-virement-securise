package com.banque.virement.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
