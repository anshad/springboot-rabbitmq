package com.myapp.springmq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class Runner {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void run(String exchange) {
        System.out.println("Sending message...");
        rabbitTemplate.convertAndSend(exchange, "foo.bar.baz", "Hello from RabbitMQ!");
    }

}
