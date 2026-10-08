package com.appzone.data_importer.common;

/** An error whose message is written for the user and is shown in the page as it is. */
public class UserFacingException extends RuntimeException {

    public UserFacingException(String message) {
        super(message);
    }
}
