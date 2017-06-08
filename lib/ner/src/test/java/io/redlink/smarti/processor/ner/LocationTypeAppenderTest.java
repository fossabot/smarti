/*
 * Copyright (c) 2016 - 2017 Redlink GmbH
 */

package io.redlink.smarti.processor.ner;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.math3.util.Precision;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.redlink.nlp.api.ProcessingData.Configuration;
import io.redlink.nlp.opennlp.pos.OpenNlpPosProcessor;
import io.redlink.nlp.api.ProcessingException;
import io.redlink.nlp.api.Processor;
import io.redlink.nlp.opennlp.OpenNlpNerProcessor;
import io.redlink.nlp.opennlp.de.LanguageGerman;
import io.redlink.nlp.opennlp.de.NerGerman;
import io.redlink.nlp.regex.ner.BahnhofDetector;
import io.redlink.nlp.regex.ner.RegexNerProcessor;
import io.redlink.smarti.model.Conversation;
import io.redlink.smarti.model.ConversationMeta;
import io.redlink.smarti.model.Message;
import io.redlink.smarti.model.Message.Origin;
import io.redlink.smarti.model.Token;
import io.redlink.smarti.model.Token.Hint;
import io.redlink.smarti.model.User;
import io.redlink.smarti.processing.ProcessingData;
import io.redlink.smarti.processor.ner.LocationTypeAppender;
import io.redlink.smarti.processor.ner.NamedEntityCollector;

/**
 * Test for the {@link LocationTypeAppender}
 * 
 * @author Rupert Westenthaler
 *
 */
public class LocationTypeAppenderTest {
    
    private static final Logger log = LoggerFactory.getLogger(LocationTypeAppenderTest.class);

    private static List<Pair<MsgData[], List<Pair<String, Hint[]>>>> CONTENTS = new ArrayList<>();

    private static List<Processor> REQUIRED_PRE_PREPERATORS;
    private LocationTypeAppender locTypeAppender;
    private static List<Processor> REQUIRED_POST_PREPERATORS;
    
    
    @BeforeClass
    public static void initClass() throws IOException, IllegalAccessException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
        CONTENTS.add(new ImmutablePair<LocationTypeAppenderTest.MsgData[], List<Pair<String,Hint[]>>>(new MsgData[]{
                new MsgData(Origin.User, "Ist der ICE 1526 von München Hbf ist heute verspätet?")},
                Arrays.asList(
                    new ImmutablePair<String, Hint[]>("München Hauptbahnhof", new Hint[]{}))));
        CONTENTS.add(new ImmutablePair<LocationTypeAppenderTest.MsgData[], List<Pair<String,Hint[]>>>(new MsgData[]{
                new MsgData(Origin.User, "Ist der ICE 1526 von München Hauptbahnhof ist heute verspätet?")},
                Arrays.asList(
                    new ImmutablePair<String, Hint[]>("München Hauptbahnhof", new Hint[]{}))));
        CONTENTS.add(new ImmutablePair<LocationTypeAppenderTest.MsgData[], List<Pair<String,Hint[]>>>(new MsgData[]{
                new MsgData(Origin.User, "Brauche einen Zug von Darmstadt Hbf nach Ilmenau Ostbf. Muss um 16:00 in Ilmenau sein.")},
                Arrays.asList(
                        new ImmutablePair<String, Hint[]>("Darmstadt Hauptbahnhof", new Hint[]{}),
                        new ImmutablePair<String, Hint[]>("Ilmenau Ostbahnhof", new Hint[]{}),
                        new ImmutablePair<String, Hint[]>("Ilmenau", new Hint[]{}))));
        CONTENTS.add(new ImmutablePair<LocationTypeAppenderTest.MsgData[], List<Pair<String,Hint[]>>>(new MsgData[]{
                new MsgData(Origin.User, "Hi, welche italienischen Restaurants könnt ihr denn in Berlin Hbf/Prenzlberg empfehlen? Danke, Hannes")},
                Arrays.asList(
                    new ImmutablePair<String, Hint[]>("Berlin Hauptbahnhof", new Hint[]{}),
                    new ImmutablePair<String, Hint[]>("Prenzlberg", new Hint[]{}))));
        
        RegexNerProcessor bhDetect = new RegexNerProcessor(Collections.singletonList(new BahnhofDetector()));
        GermanTestSetup germanNlp = GermanTestSetup.getInstance();
        REQUIRED_PRE_PREPERATORS = Arrays.asList(germanNlp.getPosProcessor(), germanNlp.getNerProcessor(), bhDetect);
        
        REQUIRED_POST_PREPERATORS = Arrays.asList(new NamedEntityCollector());
        
    }
    
    private static final Conversation initConversation(int index) {
        Conversation c = new Conversation();
        c.setMeta(new ConversationMeta());
        c.getMeta().setLastMessageAnalyzed(-1);
        c.getMeta().setStatus(ConversationMeta.Status.New);
        List<Message> messages = new ArrayList<>();
        log.trace("Conversation: ");
        for(MsgData md : CONTENTS.get(index).getLeft()){
            log.trace("    {}", md);
            messages.add(md.toMessage());
        }
        c.setMessages(messages);
        c.setUser(new User());
        c.getUser().setDisplayName("Test Dummy");
        c.getUser().setPhoneNumber("+43210123456");
        return c;
    }

    @Before
    public void init(){
        locTypeAppender = new LocationTypeAppender();
    }
    
    
    @Test
    public void testSingle() throws ProcessingException{
        int idx = Math.round((float)Math.random()*(CONTENTS.size()-1));
        //idx = 3;
        Conversation conversation = initConversation(idx);
        ProcessingData processingData = ProcessingData.create(conversation);
        processingData.getConfiguration().put(Configuration.LANGUAGE, "de");
        processConversation(processingData);
        assertNerProcessingResults(processingData, CONTENTS.get(idx).getRight());
    }

    void processConversation(ProcessingData processingData) throws ProcessingException {
        log.trace(" - preprocess conversation {}", processingData.getConversation());
        for(Processor processor : REQUIRED_PRE_PREPERATORS){
            processor.process(processingData);
        }
        log.trace(" - start processing");
        long start = System.currentTimeMillis();
        locTypeAppender.process(processingData);
        log.trace(" - processing time: {}",System.currentTimeMillis()-start);
        for(Processor qp : REQUIRED_POST_PREPERATORS){
            qp.process(processingData);
        }
        
    }

    
    @Test
    public void testMultiple() throws InterruptedException, ExecutionException {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        int numDoc = 100;
        int numWarmup = Math.max(CONTENTS.size(), 20);
        log.info("> warnup ({} calls + assertion of results)", numWarmup);
        List<Future<ConversationProcessor>> tasks = new LinkedList<>();
        for(int i = 0; i < numWarmup; i++){
            int idx = i%CONTENTS.size();
            tasks.add(executor.submit(new ConversationProcessor(idx,"de")));
        }
        while(!tasks.isEmpty()){ //wait for all the tasks to complete
            //during warmup we assert the NLP results
            ConversationProcessor cp = tasks.remove(0).get();
            assertNerProcessingResults(cp.getProcessingData(),CONTENTS.get(cp.getIdx()).getRight()); 
        }
        log.info("   ... done");
        log.info("> processing {} documents ...", numDoc);
        long min = Integer.MAX_VALUE;
        long max = Integer.MIN_VALUE;
        long sum = 0;
        for(int i = 0; i < numDoc; i++){
            int idx = i%CONTENTS.size();
            tasks.add(executor.submit(new ConversationProcessor(idx,"de")));
        }
        int i = 0;
        while(!tasks.isEmpty()){ //wait for all the tasks to complete
            ConversationProcessor completed = tasks.remove(0).get();
            i++;
            if(i%10 == 0){
                log.info(" ... {} documents processed",i);
            }
            int dur = completed.getDuration();
            if(dur > max){
                max = dur;
            }
            if(dur < min){
                min = dur;
            }
            sum = sum + dur;
        }
        log.info("Processing Times after {} documents",numDoc);
        log.info(" - average: {}ms",Precision.round(sum/(double)numDoc, 2));
        log.info(" - max: {}ms",max);
        log.info(" - min: {}ms",min);
        executor.shutdown();
    }
    
    private void assertNerProcessingResults(ProcessingData processingData, List<Pair<String,Hint[]>> expected) {
        expected = new LinkedList<>(expected); //copy so we can remove
        Conversation conv = processingData.getConversation();
        Assert.assertFalse(conv.getTokens().isEmpty());
        for(Token token : conv.getTokens()){
            log.debug("Token(idx: {}, span[{},{}], type: {}): {}", token.getMessageIdx(), token.getStart(), token.getEnd(), token.getType(), token.getValue());
            Assert.assertNotNull(token.getType());
            Assert.assertTrue(conv.getMeta().getLastMessageAnalyzed() < token.getMessageIdx());
            Assert.assertTrue(conv.getMessages().size() > token.getMessageIdx());
            Message message = conv.getMessages().get(token.getMessageIdx());
            Assert.assertTrue(message.getOrigin() == Origin.User);
            Assert.assertTrue(token.getStart() >= 0);
            Assert.assertTrue(token.getEnd() > token.getStart());
            Assert.assertTrue(token.getEnd() <= message.getContent().length());
            //the next assert is no longer true as the token.getValue() is the Lemma if present
            //Assert.assertEquals(message.getContent().substring(token.getStart(), token.getEnd()), String.valueOf(token.getValue()));
            Pair<String,Hint[]> p = expected.remove(0);
            Assert.assertEquals("Wrong Named Entity",p.getKey(), token.getValue());
            for(Hint hint : p.getValue()){
                Assert.assertTrue("Missing expected hint " + hint,token.hasHint(hint));
            }
        }
    }
    
    
    private class ConversationProcessor implements Callable<ConversationProcessor> {

        private final int idx;
        private final ProcessingData processingData;
        private int duration;

        ConversationProcessor(int idx, String lang){
            this.idx = idx;
            this.processingData = ProcessingData.create((initConversation(idx)));
            this.processingData.getConfiguration().put(Configuration.LANGUAGE, lang);
        }
        

        @Override
        public ConversationProcessor call() throws Exception {
            long start = System.currentTimeMillis();
            processConversation(processingData);
            duration = (int)(System.currentTimeMillis() - start);
            return this;
        }

        public int getIdx() {
            return idx;
        }
        
        public ProcessingData getProcessingData() {
            return processingData;
        }
        
        public int getDuration() {
            return duration;
        }
    }
    
    private static class  MsgData {
        
        final Origin origin;
        final String msg;
        final Date date;

        MsgData(Message.Origin origin, String msg){
            this(origin,msg,new Date());
        }
        MsgData(Message.Origin origin, String msg, Date date){
            this.origin = origin;
            this.msg = msg;
            this.date = date;
        }
        
        Message toMessage(){
            Message message = new Message();
            message.setContent(msg);
            message.setOrigin(origin);
            message.setTime(date);
            return message;
        }
        
        @Override
        public String toString() {
            return origin + ": "+ msg;
        }
        
    }
    
}
