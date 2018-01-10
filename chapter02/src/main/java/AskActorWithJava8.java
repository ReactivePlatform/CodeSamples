/*
 * Copyright 2017 https://www.reactivedesignpatterns.com/ & http://rdp.reactiveplatform.xyz/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.Creator;
import akka.pattern.Patterns.*;
import akka.pattern.PatternsCS;
import akka.util.Timeout;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static akka.pattern.PatternsCS.ask;


public class AskActorWithJava8 {
    public static class Request{
        private final int reqId;

        public Request(int reqId) {
            this.reqId = reqId;
        }

        public int getReqId() {
            return reqId;
        }
    }
    public static class Response {}
    public static class MyActor extends AbstractActor{
        @Override
        public Receive createReceive() {
            return receiveBuilder()
                    .matchAny((msg) -> {
                        getSender().tell(new Response(),getSelf());
                    })
                    .build();
        }
    }

    public static void processIt(Response response){
        System.out.println(response);
    }
    private static final ActorSystem ACTOR_SYSTEM = ActorSystem.create();
    public static void main(String[] args) {
        ActorRef actorRef = ACTOR_SYSTEM.actorOf(Props.create(MyActor.class, (Creator<MyActor>) MyActor::new));
        Request request = new Request(1);
        Timeout timeout = Timeout.apply(1, TimeUnit.SECONDS);

        // #snip
        CompletionStage<Response> future =
                ask(actorRef, request, timeout)
                        .thenApply(Response.class::cast);
        future.thenAccept(response -> AskActorWithJava8.processIt(response));
        // #snip

    }
}
