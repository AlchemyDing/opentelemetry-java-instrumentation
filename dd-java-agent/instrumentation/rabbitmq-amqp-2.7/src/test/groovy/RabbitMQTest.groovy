import com.rabbitmq.client.AMQP
import com.rabbitmq.client.AlreadyClosedException
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Consumer
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.GetResponse
import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import io.opentracing.tag.Tags
import org.springframework.amqp.core.AmqpAdmin
import org.springframework.amqp.core.AmqpTemplate
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.testcontainers.containers.GenericContainer
import spock.lang.Requires
import spock.lang.Shared

import java.util.concurrent.Phaser

// Do not run tests locally on Java7 since testcontainers are not compatible with Java7
// It is fine to run on CI because CI provides rabbitmq externally, not through testcontainers
@Requires({ "true" == System.getenv("CI") || jvm.java8Compatible })
class RabbitMQTest extends AgentTestRunner {

  /*
    Note: type here has to stay undefined, otherwise tests will fail in CI in Java 7 because
    'testcontainers' are built for Java 8 and Java 7 cannot load this class.
   */
  @Shared
  def rabbbitMQContainer
  @Shared
  def defaultRabbitMQPort = 5672
  @Shared
  InetSocketAddress rabbitmqAddress = new InetSocketAddress("127.0.0.1", defaultRabbitMQPort)

  ConnectionFactory factory = new ConnectionFactory(host: rabbitmqAddress.hostName, port: rabbitmqAddress.port)
  Connection conn = factory.newConnection()
  Channel channel = conn.createChannel()

  def setupSpec() {

    /*
      CI will provide us with rabbitmq container running along side our build.
      When building locally, however, we need to take matters into our own hands
      and we use 'testcontainers' for this.
     */
    if ("true" != System.getenv("CI")) {
      rabbbitMQContainer = new GenericContainer('rabbitmq:latest')
        .withExposedPorts(defaultRabbitMQPort)
//        .withLogConsumer { output ->
//        print output.utf8String
//      }
      rabbbitMQContainer.start()
      rabbitmqAddress = new InetSocketAddress(
        rabbbitMQContainer.containerIpAddress,
        rabbbitMQContainer.getMappedPort(defaultRabbitMQPort)
      )
    }
  }

  def cleanupSpec() {
    if (rabbbitMQContainer) {
      rabbbitMQContainer.stop()
    }
  }

  def cleanup() {
    try {
      channel.close()
      conn.close()
    } catch (AlreadyClosedException e) {
      // Ignore
    }
  }

  def "test rabbit publish/get"() {
    setup:
    channel.exchangeDeclare(exchangeName, "direct", false)
    String queueName = channel.queueDeclare().getQueue()
    channel.queueBind(queueName, exchangeName, routingKey)

    channel.basicPublish(exchangeName, routingKey, null, "Hello, world!".getBytes())

    GetResponse response = channel.basicGet(queueName, true)

    expect:
    new String(response.getBody()) == "Hello, world!"

    and:
    assertTraces(5) {
      trace(0, 1) {
        rabbitSpan(it, "exchange.declare")
      }
      trace(1, 1) {
        rabbitSpan(it, "queue.declare")
      }
      trace(2, 1) {
        rabbitSpan(it, "queue.bind")
      }
      trace(3, 1) {
        rabbitSpan(it, "basic.publish $exchangeName")
      }
      trace(4, 1) {
        rabbitSpan(it, "basic.get <generated>", TEST_WRITER[3][0])
      }
    }

    where:
    exchangeName    | routingKey
    "some-exchange" | "some-routing-key"
  }

  def "test rabbit publish/get default exchange"() {
    setup:
    String queueName = channel.queueDeclare("some-routing-queue", false, true, true, null).getQueue()
    String routingKey = queueName

    channel.basicPublish("", routingKey, null, "Hello, world!".getBytes())

    GetResponse response = channel.basicGet(queueName, true)

    expect:
    new String(response.getBody()) == "Hello, world!"

    and:
    assertTraces(3) {
      trace(0, 1) {
        rabbitSpan(it, "queue.declare")
      }
      trace(1, 1) {
        rabbitSpan(it, "basic.publish <default>")
      }
      trace(2, 1) {
        rabbitSpan(it, "basic.get some-routing-queue", TEST_WRITER[1][0])
      }
    }
  }

  def "test rabbit consume #messageCount messages"() {
    setup:
    channel.exchangeDeclare(exchangeName, "direct", false)
    String queueName = (messageCount % 2 == 0) ?
      channel.queueDeclare().getQueue() :
      channel.queueDeclare("some-queue", false, true, true, null).getQueue()
    channel.queueBind(queueName, exchangeName, "")

    def phaser = new Phaser()
    phaser.register()
    phaser.register()
    def deliveries = []

    Consumer callback = new DefaultConsumer(channel) {
      @Override
      void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
        phaser.arriveAndAwaitAdvance() // Ensure publish spans are reported first.
        deliveries << new String(body)
      }
    }

    channel.basicConsume(queueName, callback)

    (1..messageCount).each {
      TEST_WRITER.waitForTraces(2 + (it * 2))
      channel.basicPublish(exchangeName, "", null, "msg $it".getBytes())
      TEST_WRITER.waitForTraces(3 + (it * 2))
      phaser.arriveAndAwaitAdvance()
    }
    def resource = messageCount % 2 == 0 ? "basic.deliver <generated>" : "basic.deliver $queueName"

    expect:
    assertTraces(4 + (messageCount * 2)) {
      trace(0, 1) {
        rabbitSpan(it, "exchange.declare")
      }
      trace(1, 1) {
        rabbitSpan(it, "queue.declare")
      }
      trace(2, 1) {
        rabbitSpan(it, "queue.bind")
      }
      trace(3, 1) {
        rabbitSpan(it, "basic.consume")
      }
      (1..messageCount).each {
        def publishSpan = null
        trace(2 + (it * 2), 1) {
          publishSpan = span(0)
          rabbitSpan(it, "basic.publish $exchangeName")
        }
        trace(3 + (it * 2), 1) {
          rabbitSpan(it, resource, publishSpan)
        }
      }
    }

    deliveries == (1..messageCount).collect { "msg $it" }

    where:
    exchangeName = "some-exchange"
    messageCount << (1..4)
  }

  def "test rabbit error (#command)"() {
    when:
    closure.call(channel)

    then:
    def throwable = thrown(exception)

    and:

    assertTraces(1) {
      trace(0, 1) {
        rabbitSpan(it, command, null, throwable, errorMsg)
      }
    }

    where:
    command                 | exception             | errorMsg                                           | closure
    "exchange.declare"      | IOException           | null                                               | {
      it.exchangeDeclare("some-exchange", "invalid-type", true)
    }
    "Channel.basicConsume"  | IllegalStateException | "Invalid configuration: 'queue' must be non-null." | {
      it.basicConsume(null, null)
    }
    "basic.get <generated>" | IOException           | null                                               | {
      it.basicGet("amq.gen-invalid-channel", true)
    }
  }

  def "test spring rabbit"() {
    setup:
    def connectionFactory = new CachingConnectionFactory(rabbitmqAddress.hostName, rabbitmqAddress.port)
    AmqpAdmin admin = new RabbitAdmin(connectionFactory)
    def queue = new Queue("some-routing-queue", false, true, true, null)
    admin.declareQueue(queue)
    AmqpTemplate template = new RabbitTemplate(connectionFactory)
    template.convertAndSend(queue.name, "foo")
    String message = (String) template.receiveAndConvert(queue.name)

    expect:
    message == "foo"

    and:
    assertTraces(3) {
      trace(0, 1) {
        rabbitSpan(it, "queue.declare")
      }
      trace(1, 1) {
        rabbitSpan(it, "basic.publish <default>")
      }
      trace(2, 1) {
        rabbitSpan(it, "basic.get $queue.name", TEST_WRITER[1][0])
      }
    }
  }

  def rabbitSpan(TraceAssert trace, String resource, DDSpan parentSpan = null, Throwable exception = null, String errorMsg = null) {
    trace.span(0) {
      serviceName "rabbitmq"
      operationName "amqp.command"
      resourceName resource

      if (parentSpan) {
        childOf parentSpan
      } else {
        parent()
      }

      errored exception != null

      tags {
        if (exception) {
          errorTags(exception.class, errorMsg)
        }
        "$Tags.COMPONENT.key" "rabbitmq-amqp"
        "$Tags.PEER_HOSTNAME.key" { it == null || it instanceof String }
        "$Tags.PEER_PORT.key" { it == null || it instanceof Integer }

        switch (tag("amqp.command")) {
          case "basic.publish":
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_PRODUCER
            "$DDTags.SPAN_TYPE" DDSpanTypes.MESSAGE_PRODUCER
            "amqp.command" "basic.publish"
            "amqp.exchange" { it == null || it == "some-exchange" }
            "amqp.routing_key" { it == null || it == "some-routing-key" || it == "some-routing-queue" }
            "amqp.delivery_mode" { it == null || it == 2 }
            "message.size" Integer
            break
          case "basic.get":
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CONSUMER
            "$DDTags.SPAN_TYPE" DDSpanTypes.MESSAGE_CONSUMER
            "amqp.command" "basic.get"
            "amqp.queue" { it == "some-queue" || it == "some-routing-queue" || it.startsWith("amq.gen-") }
            "message.size" { it == null || it instanceof Integer }
            break
          case "basic.deliver":
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CONSUMER
            "$DDTags.SPAN_TYPE" DDSpanTypes.MESSAGE_CONSUMER
            "amqp.command" "basic.deliver"
            "span.origin.type" "RabbitMQTest\$1"
            "amqp.exchange" "some-exchange"
            "message.size" Integer
            break
          default:
            "$Tags.SPAN_KIND.key" Tags.SPAN_KIND_CLIENT
            "$DDTags.SPAN_TYPE" DDSpanTypes.MESSAGE_CLIENT
            "amqp.command" { it == null || it == resource }
        }
        defaultTags()
      }
    }
  }
}
