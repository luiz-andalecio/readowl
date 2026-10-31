package br.com.readowl.readowl.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record EmailRequest(@NotBlank @Email @Size(max = 255) String email) {
}
