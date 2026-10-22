package com.agentforge.app.channel;

import com.agentforge.infrastructure.channel.WeComCallbackHandler;
import com.agentforge.infrastructure.channel.WeComCallbackRejectedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * WeCom smart-bot callback URL. Deliberately outside {@code /api/v1}: WeCom sends no identity
 * header, and the message signature is the authentication.
 */
@RestController
@ConditionalOnProperty(
        prefix = "agentforge.channel.wecom",
        name = "enabled",
        havingValue = "true")
@RequestMapping("/callbacks/wecom/bot")
public class WeComBotCallbackController {

    private final WeComCallbackHandler handler;

    public WeComBotCallbackController(WeComCallbackHandler handler) {
        this.handler = handler;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> verify(
            @RequestParam("msg_signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestParam("echostr") String echoStr) {
        return Mono.fromCallable(() -> handler.verifyUrl(signature, timestamp, nonce, echoStr));
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> receive(
            @RequestParam("msg_signature") String signature,
            @RequestParam("timestamp") String timestamp,
            @RequestParam("nonce") String nonce,
            @RequestBody String body) {
        // Records the message in MySQL before answering, so it runs off the event loop.
        return Mono.fromCallable(() -> handler.handle(signature, timestamp, nonce, body))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** No body: a forged or unreadable callback learns nothing from the answer. */
    @ExceptionHandler(WeComCallbackRejectedException.class)
    public ResponseEntity<Void> rejected(WeComCallbackRejectedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
