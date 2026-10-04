// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise

import io.github.xiaomeng2568.meldwise.data.*
import io.github.xiaomeng2568.meldwise.provider.*

internal object DebateFixtures {
    val a=DebateModel(ModelRef("deepseek","a"),"PRIVATE_MODEL_A",ReasoningPreference.High)
    val b=DebateModel(ModelRef("chatgpt","b"),"PRIVATE_MODEL_B")
    val judge=DebateModel(ModelRef("deepseek","judge"),"PRIVATE_JUDGE",ReasoningPreference.Low)
    val config=DebateConfig(a,b,judge)
    val same=DebateConfig(a,a.copy(ref=ModelRef("deepseek","b")),a)
    fun round(config:DebateConfig=this.config,prior:List<FrozenVisibleInput> = emptyList())=DebateRound(
        "round","user",config,DebateStageType.entries.map {DebateStage("id-$it",it,config.modelFor(it))},
        prior+FrozenVisibleInput(MessageRole.USER,"CURRENT_QUESTION"),7,10,10)
    fun change(r:DebateRound,type:DebateStageType,state:DebateStageState,output:String="visible-$type",
        error:ErrorKind?=null,reasoning:ReasoningRecord=ReasoningRecord()):DebateRound {
        val next=r.copy(stages=r.stages.map {if(it.type==type) it.copy(state=state,
            output=if(state in setOf(DebateStageState.Pending,DebateStageState.NotRun)) "" else output,error=error,reasoning=reasoning,
            startedAt=if(state in setOf(DebateStageState.Pending,DebateStageState.NotRun)) null else 10) else it})
        return next.copy(lifecycle=debateRoundLifecycle(next.stages))
    }
    fun complete(r:DebateRound=round(),types:List<DebateStageType> = DebateStageType.entries)=types.fold(r) {v,t->change(v,t,DebateStageState.Complete)}
    fun conversation(r:DebateRound=round())=Conversation("conversation",r.config.modelA.ref,
        listOf(ChatMessage(r.userMessageId,null,MessageRole.USER,r.frozenInput.last().text,MessageState.COMPLETED,order=0,timestamp=10,modelRef=r.config.modelA.ref)),
        "title",10,10,mode=ConversationMode.Debate,debate=r.config,debateRounds=listOf(r))
}
