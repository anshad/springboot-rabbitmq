# springboot-rabbitmq
Springboot rabbitmq integration, follow the steps.

# Set up RabbitMQ broker

Before you can build your messaging application, you need to set up the server that will handle receiving and sending messages.

RabbitMQ is an AMQP server. The server is freely available at http://www.rabbitmq.com/download.html. You can download it manually, or if you are using a Mac with homebrew:

``brew install rabbitmq``

Unpack the server and launch it with default settings.

``rabbitmq-server``

You can also use Docker Compose to quickly launch a RabbitMQ server if you have docker running locally. There is a ``docker-compose.yml`` in the root. It is very simple:

    rabbitmq:
      image: rabbitmq:management
      ports:
        - "5672:5672"
        - "15672:15672"
        
With this file in the current directory you can run ``docker-compose up`` to get RabbitMQ running in a container.

> *Once installed and started, you can access RabbitMQ management server at ``http://localhost:15672/#/`` with default username and password as `guest`.*

# Create a RabbitMQ message receiver

With any messaging-based application, you need to create a receiver that will respond to published messages.

``src/main/java/com.myapp.springmq/Receiver.java``

    package com.myapp.springmq;

    import java.util.concurrent.CountDownLatch;
    import org.springframework.stereotype.Component;

    @Component
    public class Receiver {

        private CountDownLatch latch = new CountDownLatch(1);

        public void receiveMessage(String message) {
            System.out.println("Received <" + message + ">");
            latch.countDown();
        }

        public CountDownLatch getLatch() {
            return latch;
        }

    }


The Receiver is a simple POJO that defines a method for receiving messages. When you register it to receive messages, you can name it anything you want.

> *For convenience, this POJO also has a CountDownLatch. This allows it to signal that the message is received. This is something you are not likely to implement in a production application.*

# Register the listener and send a message

Spring AMQP’s ``RabbitTemplate`` provides everything you need to send and receive messages with RabbitMQ. Specifically, you need to configure:

- A message listener container
- Declare the queue, the exchange, and the binding between them
- A component to send some messages to test the listener

> *Spring Boot automatically creates a connection factory and a RabbitTemplate, reducing the amount of code you have to write.*

You’ll use RabbitTemplate to send messages, and you will register a Receiver with the message listener container to receive messages. The connection factory drives both, allowing them to connect to the RabbitMQ server.

``src/main/java/com.myapp.springmq/Application.java``

    package com.myapp.springmq;

    import org.springframework.amqp.core.BindingBuilder;
    import org.springframework.amqp.core.TopicExchange;
    import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
    import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
    import org.springframework.boot.SpringApplication;
    import org.springframework.boot.autoconfigure.SpringBootApplication;
    import org.springframework.context.annotation.Bean;
    import org.springframework.amqp.core.Binding;
    import org.springframework.amqp.core.Queue;
    import org.springframework.amqp.rabbit.connection.ConnectionFactory;

    @SpringBootApplication
    public class SpringmqApplication {

        static final String topicExchangeName = "spring-boot-exchange";

        static final String queueName = "spring-boot";

        @Bean
        Queue queue() {
            return new Queue(queueName, false);
        }

        @Bean
        TopicExchange exchange() {
            return new TopicExchange(topicExchangeName);
        }

        @Bean
        Binding binding(Queue queue, TopicExchange exchange) {
            return BindingBuilder.bind(queue).to(exchange).with("foo.bar.#");
        }

        @Bean
        SimpleMessageListenerContainer container(ConnectionFactory connectionFactory,
                                                 MessageListenerAdapter listenerAdapter) {
            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.setQueueNames(queueName);
            container.setMessageListener(listenerAdapter);
            return container;
        }

        @Bean
        MessageListenerAdapter listenerAdapter(Receiver receiver) {
            return new MessageListenerAdapter(receiver, "receiveMessage");
        }

        public static void main(String[] args) {
            SpringApplication.run(SpringmqApplication.class, args);
        }

    }
    
    

``@SpringBootApplication`` is a convenience annotation that adds all of the following:

 - ``@Configuration`` tags the class as a source of bean definitions for the application context.
 - ``@EnableAutoConfiguration`` tells Spring Boot to start adding beans based on classpath settings, other beans, and various property settings.
 - Normally you would add ``@EnableWebMvc`` for a Spring MVC app, but Spring Boot adds it automatically when it sees ``spring-webmvc`` on the classpath. This flags the application as a web application and activates key behaviors such as setting up a ``DispatcherServlet``.
 - ``@ComponentScan`` tells Spring to look for other components, configurations, and services in the ``com.myapp.springmq`` package, allowing it to find the controllers.

The ``main()`` method uses Spring Boot’s ``SpringApplication.run()`` method to launch an application. Did you notice that there wasn’t a single line of XML? No ``web.xml`` file either. This web application is 100% pure Java and you didn’t have to deal with configuring any plumbing or infrastructure.

The bean defined in the ``listenerAdapter()`` method is registered as a message listener in the container defined in ``container()``. It will listen for messages on the "spring-boot" queue. Because the ``Receiver`` class is a POJO, it needs to be wrapped in the ``MessageListenerAdapter``, where you specify it to invoke ``receiveMessage``.
> *JMS queues and AMQP queues have different semantics. For example, JMS sends queued messages to only one consumer. While AMQP queues do the same thing, AMQP producers don’t send messages directly to queues. Instead, a message is sent to an exchange, which can go to a single queue, or fanout to multiple queues, emulating the concept of JMS topics.* 



The message listener container and receiver beans are all you need to listen for messages. To send a message, you also need a Rabbit template.

The ``queue()`` method creates an AMQP queue. The ``exchange()`` method creates a topic exchange. The ``binding()`` method binds these two together, defining the behavior that occurs when RabbitTemplate publishes to an exchange.

> *Spring AMQP requires that the ``Queue``, the ``TopicExchange``, and the ``Binding`` be declared as top level Spring beans in order to be set up properly.*

In this case, we use a topic exchange and the queue is bound with routing key ``foo.bar.#`` which means any message sent with a routing key beginning with ``foo.bar``. will be routed to the queue.

# Send a Test Message

Test messages are sent by a CommandLineRunner, which also waits for the latch in the receiver and closes the application context:

``src/main/java/com.myapp.springmq/Runner.java``

    package com.myapp.springmq;

    import java.util.concurrent.TimeUnit;

    import org.springframework.amqp.rabbit.core.RabbitTemplate;
    import org.springframework.boot.CommandLineRunner;
    import org.springframework.stereotype.Component;

    @Component
    public class Runner implements CommandLineRunner {

        private final RabbitTemplate rabbitTemplate;
        private final Receiver receiver;

        public Runner(Receiver receiver, RabbitTemplate rabbitTemplate) {
            this.receiver = receiver;
            this.rabbitTemplate = rabbitTemplate;
        }

        @Override
        public void run(String... args) throws Exception {
            System.out.println("Sending message...");
            rabbitTemplate.convertAndSend(SpringmqApplication.topicExchangeName, "foo.bar.baz", "Hello from RabbitMQ!");
            receiver.getLatch().await(10000, TimeUnit.MILLISECONDS);
        }

    }
    


Notice that the template routes the message to the exchange, with a routing key of ``foo.bar.baz`` which matches the binding.

The runner can be mocked out in tests, so that the receiver can be tested in isolation.


# Run the Application

The ``main()`` method starts that process by creating a Spring application context. This starts the message listener container, which will start listening for messages. There is a ``Runner`` bean which is then automatically executed: it retrieves the ``RabbitTemplate`` from the application context and sends a "Hello from RabbitMQ!" message on the "spring-boot" queue. Finally, it closes the Spring application context and the application ends.




