/*
 * Copyright (c) 2016 - 2017 Redlink GmbH
 */

package io.redlink.reisebuddy.extractor.token.impl;

import io.redlink.nlp.api.ProcessingData;
import io.redlink.nlp.api.Processor;
import io.redlink.reisebuddy.extractor.token.TokenProcessingRuleset;
import io.redlink.smarti.model.Conversation;
import io.redlink.smarti.model.Message;
import io.redlink.smarti.model.Message.Origin;
import io.redlink.smarti.model.MessageTopic;
import io.redlink.smarti.model.State;
import io.redlink.smarti.model.Token;
import io.redlink.smarti.model.Token.Type;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static io.redlink.smarti.processing.SmartiAnnotations.CONVERSATION_ANNOTATION;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link QueryPreparator} that applies all the registered
 * {@link TokenProcessingRuleset}s. This runs in {@link Phase#post}
 * @author Rupert Westenthaler
 *
 */
@Component
public class TokenProcessor extends Processor {

    /**
     * The minimum confidence for topics (currently none)
     */
    private static final float MIN_TOPIC_CONF = 0f;
    private final Collection<TokenProcessingRuleset> rulesets;

    @Autowired
    public TokenProcessor(Collection<TokenProcessingRuleset> rulesets) {
        super("token.processor","Token Processor",Phase.post);
        this.rulesets = rulesets;
    }
    
    @Override
    public Map<String, Object> getDefaultConfiguration() {
        return Collections.emptyMap();
    }
    
    @Override
    protected void init() throws Exception {
        //No op
    }
    
    @Override
    protected void doProcessing(ProcessingData processingData) {
        //(1) filter the rule sets based on the language of the conversation
        String language = processingData.getLanguage();
        List<TokenProcessingRuleset> rulesets = this.rulesets.stream()
                .filter(rs -> rs.getLanguage() == null || language.equals(rs.getLanguage()))
                .collect(Collectors.toList());
        if(rulesets.isEmpty()){
            return; //no ruleset for this language
        }
        
        Conversation conv = processingData.getAnnotation(CONVERSATION_ANNOTATION);
        if(conv == null){
            log.warn("parsed {} does not have a '{}' annotation", processingData, CONVERSATION_ANNOTATION);
            return;
        }
        final List<Message> messages = conv.getMessages();
        final List<Token> tokens = conv.getTokens();

        //(2) get the messages we want to process
        int lastAnalyzed = conv.getMeta().getLastMessageAnalyzed();
        for(int i = lastAnalyzed + 1; i < messages.size(); i++){
            final int mIdx = i;
            final Message message = messages.get(mIdx);
            if(log.isDebugEnabled()){
                log.debug(" - Message {}:{}", mIdx,StringUtils.abbreviate(message.getContent(), 50));
            }
            if(message.getOrigin() == Origin.User){
                List<Token> msgTokens = tokens.stream() //the tokes of the current message
                        .filter(t -> t.getMessageIdx() == mIdx)
                        .filter(t -> t.getState() != State.Rejected)
                        .collect(Collectors.toList());
                //transitive closure over the MessageTopics of the current message
                EnumSet<MessageTopic> topics = msgTokens.stream()
                        .filter(t -> t.getType() == Type.Topic)
                        .filter(t -> t.getConfidence() >= MIN_TOPIC_CONF)
                        .map(Token::getValue)
                        .filter(v -> v instanceof MessageTopic)
                        .map(MessageTopic.class::cast)
                        .map(MessageTopic::hierarchy)
                        .flatMap(s -> s.stream())
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(MessageTopic.class)));
                //(3) apply the matching rule sets
                for(TokenProcessingRuleset ruleset : rulesets){
                    if(!Collections.disjoint(topics, ruleset.topics())){
                        List<Token> rulesetTokens = msgTokens.stream()
                                .filter(t -> ruleset.tokenTypes().contains(t.getType()))
                                .collect(Collectors.toList());
                        if(!rulesetTokens.isEmpty()){
                            log.debug("   apply {} ",ruleset.getClass().getSimpleName());
                            ruleset.apply(message, rulesetTokens);
                        }
                    }
                }
            } //else ignore agent messages
        }
    }

    
    
}