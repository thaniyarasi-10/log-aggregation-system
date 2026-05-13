package com.kovanlabs.logcontroller.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailTestController {

    @Autowired
    private JavaMailSender mailSender;

    @GetMapping("/test/mail")
    public String sendMail() {

        SimpleMailMessage message =
                new SimpleMailMessage();

        message.setTo("thaniyarasik@gmail.com");

        message.setSubject("Test Email");

        message.setText(
                "Email alerts working successfully!"
        );

        mailSender.send(message);

        return "Mail sent successfully";
    }
}