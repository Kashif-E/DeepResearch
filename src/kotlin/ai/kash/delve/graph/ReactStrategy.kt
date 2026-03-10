package ai.kash.delve.graph

import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*

// Koog's AIAgent counts each LLM request, tool execution, and tool result as separate iterations.
// A single logical "turn" (think → search → observe) consumes ~3-5 framework iterations.
// Multiply the semantic limit by this factor to prevent premature termination.
const val ITERATIONS_PER_TOOL_TURN = 10

// Shared ReAct strategy: LLM -> tool call -> execute -> send result -> loop
// Used by both supervisor and researcher agents.
val reactStrategy = strategy<String, String>("react") {
    val callLLM by nodeLLMRequest()
    val executeTool by nodeExecuteTool()
    val sendToolResult by nodeLLMSendToolResult()

    edge(nodeStart forwardTo callLLM)
    edge(callLLM forwardTo executeTool onToolCall { true })
    edge(callLLM forwardTo nodeFinish onAssistantMessage { true })
    edge(executeTool forwardTo sendToolResult)
    edge(sendToolResult forwardTo executeTool onToolCall { true })
    edge(sendToolResult forwardTo callLLM onAssistantMessage { true })
}
