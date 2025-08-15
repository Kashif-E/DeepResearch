package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.*
import ai.koog.agents.ext.tool.SayToUser
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

data class OllamaConfig(
    val baseUrl: String = "http://localhost:11434",
    val model: String = "gpt-oss:20b",
    val temperature: Double = 0.7
) {
    companion object {
        fun default() = OllamaConfig()
    }
}

// Mom's "Phone" - to call venues, caterers, friends
object PhoneTool : SimpleTool<PhoneTool.Args>() {
    @Serializable
    data class Args(
        val contactType: String, // "venue", "caterer", "friend", "entertainment"
        val purpose: String // what you're calling about
    ) : ToolArgs

    override val argsSerializer: KSerializer<Args> = Args.serializer()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "make_phone_call",
        description = "Mom's phone tool to call venues, caterers, friends, or entertainment services for party planning",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "contactType",
                description = "Type of contact: 'venue', 'caterer', 'friend', 'entertainment', 'decoration_store'",
                type = ToolParameterType.String
            ),
            ToolParameterDescriptor(
                name = "purpose",
                description = "What you're calling about (e.g., 'book birthday party venue', 'order birthday cake')",
                type = ToolParameterType.String
            )
        )
    )

    private val contacts = mapOf(
        "venue" to listOf("Community Center", "Pizza Palace", "Local Park Pavilion", "Chuck E. Cheese"),
        "caterer" to listOf("Sweet Dreams Bakery", "Party Pizza Co.", "Hometown Deli"),
        "entertainment" to listOf("DJ Mike", "Bounce House Rentals", "Face Painting by Sarah"),
        "decoration_store" to listOf("Party City", "Dollar Store", "Walmart"),
        "friend" to listOf("Sarah's Mom", "Jessica's Mom", "Tommy's Dad")
    )

    override suspend fun doExecute(args: Args): String {
        val availableContacts = contacts[args.contactType] ?: return "I don't have contacts for ${args.contactType}"

        println("📞 Mom is calling ${args.contactType} about: ${args.purpose}")

        return when (args.contactType) {
            "venue" -> {
                val venue = availableContacts.random()
                "✅ Called $venue: Available Saturday 2-6pm for \$150. Includes tables, chairs, and cleanup!"
            }

            "caterer" -> {
                val caterer = availableContacts.random()
                "✅ Called $caterer: Can make a custom birthday cake for \$45, pizza party package for 12 kids is \$80"
            }

            "entertainment" -> {
                val entertainer = availableContacts.random()
                "✅ Called $entertainer: Available for 3-hour party booking at \$200, includes setup and cleanup"
            }

            "decoration_store" -> {
                val store = availableContacts.random()
                "✅ Called $store: Birthday theme decorations in stock - balloons, banners, party hats for about \$50"
            }

            "friend" -> {
                val friend = availableContacts.random()
                "✅ Called $friend: They can help with setup and their kid is excited to come! They'll bring chips and drinks."
            }

            else -> "Called about ${args.purpose} - got some good information!"
        }
    }
}

// Mom's "Car" - to drive places and pick things up
object CarTool : SimpleTool<CarTool.Args>() {
    @Serializable
    data class Args(
        val destination: String,
        val purpose: String
    ) : ToolArgs

    override val argsSerializer: KSerializer<Args> = Args.serializer()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "drive_to_location",
        description = "Mom's car tool to drive to stores, venues, or other locations to pick things up or handle tasks",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "destination",
                description = "Where to drive (e.g., 'bakery', 'party store', 'venue', 'grocery store')",
                type = ToolParameterType.String
            ),
            ToolParameterDescriptor(
                name = "purpose",
                description = "What to do there (e.g., 'pick up cake', 'buy decorations', 'check out venue')",
                type = ToolParameterType.String
            )
        )
    )

    override suspend fun doExecute(args: Args): String {
        println("🚗 Mom is driving to ${args.destination} to ${args.purpose}")

        return when (args.destination.lowercase()) {
            "bakery" -> "✅ Drove to bakery: Picked up the custom birthday cake - it looks amazing! The kids will love it."
            "party store", "party city" -> "✅ Drove to party store: Got balloons, streamers, party hats, and themed decorations. Total: \$52"
            "grocery store" -> "✅ Drove to grocery store: Picked up snacks, drinks, ice cream, and paper plates. Everything we need!"
            "venue" -> "✅ Drove to venue: Checked it out in person - perfect space, clean, and the staff is helpful!"
            "dollar store" -> "✅ Drove to dollar store: Found great budget decorations and party favors for the goodie bags!"
            else -> "✅ Drove to ${args.destination}: Completed task - ${args.purpose}. Everything is coming together!"
        }
    }
}

// Mom's "Computer" - to research and book online
object ComputerTool : SimpleTool<ComputerTool.Args>() {
    @Serializable
    data class Args(
        val searchTopic: String,
        val task: String // "research", "book", "compare_prices", "read_reviews"
    ) : ToolArgs

    override val argsSerializer: KSerializer<Args> = Args.serializer()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "use_computer",
        description = "Mom's computer tool to research party ideas, compare prices, read reviews, or book services online",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "searchTopic",
                description = "What to research (e.g., 'birthday party themes', 'party venues near me', 'DIY decorations')",
                type = ToolParameterType.String
            ),
            ToolParameterDescriptor(
                name = "task",
                description = "What to do: 'research', 'book', 'compare_prices', 'read_reviews'",
                type = ToolParameterType.String
            )
        )
    )

    override suspend fun doExecute(args: Args): String {
        println("💻 Mom is using computer to ${args.task} about: ${args.searchTopic}")

        return when (args.task) {
            "research" -> {
                "✅ Researched ${args.searchTopic}: Found great ideas! Pinterest has amazing DIY decorations, and there are themes like superheroes, princess, sports, or gaming that kids love."
            }

            "compare_prices" -> {
                "✅ Compared prices for ${args.searchTopic}: Found the best deals - local venues are cheaper than chains, and buying decorations in bulk saves money!"
            }

            "read_reviews" -> {
                "✅ Read reviews for ${args.searchTopic}: Great feedback! Most parents say kids had amazing time, good value for money, and staff was helpful."
            }

            "book" -> {
                "✅ Booked ${args.searchTopic} online: Confirmed reservation! Got confirmation email and all the details."
            }

            else -> "✅ Used computer for ${args.task}: Got the information we needed for ${args.searchTopic}!"
        }
    }
}

// Mom's "Network of Friends" - to get recommendations and help
object FriendsNetworkTool : SimpleTool<FriendsNetworkTool.Args>() {
    @Serializable
    data class Args(
        val requestType: String, // "recommendation", "help", "advice", "invitation"
        val topic: String
    ) : ToolArgs

    override val argsSerializer: KSerializer<Args> = Args.serializer()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "contact_mom_friends",
        description = "Mom's network tool to get recommendations, ask for help, or send invitations through her mom friends",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "requestType",
                description = "Type of request: 'recommendation', 'help', 'advice', 'invitation'",
                type = ToolParameterType.String
            ),
            ToolParameterDescriptor(
                name = "topic",
                description = "What you need (e.g., 'birthday venue recommendations', 'help with setup', 'party planning advice')",
                type = ToolParameterType.String
            )
        )
    )

    override suspend fun doExecute(args: Args): String {
        println("👥 Mom is reaching out to her network for ${args.requestType} about: ${args.topic}")

        return when (args.requestType) {
            "recommendation" -> {
                "✅ Asked mom friends for recommendations: Sarah recommends Pizza Palace (great for kids), Lisa loves the community center (affordable), and Jenny suggests outdoor parties (weather permitting)!"
            }

            "help" -> {
                "✅ Asked for help with ${args.topic}: Three moms volunteered! Sarah will help with decorations, Lisa can assist with setup, and Jenny will help with cleanup."
            }

            "advice" -> {
                "✅ Got advice about ${args.topic}: Experienced moms say keep it simple, have backup activities, and don't forget goodie bags. Most importantly - take lots of pictures!"
            }

            "invitation" -> {
                "✅ Sent invitations through mom network: All the kids' friends are invited! Parents confirmed attendance and offered to help bring snacks and drinks."
            }

            else -> "✅ Contacted mom network about ${args.topic}: Got great support from other parents!"
        }
    }
}

// Mom's "Credit Card" - to make purchases
object CreditCardTool : SimpleTool<CreditCardTool.Args>() {
    @Serializable
    data class Args(
        val item: String,
        val amount: Double,
        val store: String
    ) : ToolArgs

    override val argsSerializer: KSerializer<Args> = Args.serializer()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "make_purchase",
        description = "Mom's credit card tool to purchase party supplies, food, decorations, or services",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "item",
                description = "What to buy (e.g., 'birthday cake', 'decorations', 'venue booking', 'entertainment')",
                type = ToolParameterType.String
            ),
            ToolParameterDescriptor(
                name = "amount",
                description = "Cost in dollars",
                type = ToolParameterType.Float
            ),
            ToolParameterDescriptor(
                name = "store",
                description = "Where purchasing from",
                type = ToolParameterType.String
            )
        )
    )

    private var totalSpent = 0.0

    override suspend fun doExecute(args: Args): String {
        totalSpent += args.amount
        println("💳 Mom is purchasing ${args.item} for $${args.amount} from ${args.store}")

        return "✅ Purchased ${args.item} for $${args.amount} from ${args.store}. " +
                "Transaction approved! Total party budget spent so far: $${String.format("%.2f", totalSpent)}"
    }
}

// Mom's "Planning Brain" - to track and organize everything
object PlanningTool : SimpleTool<PlanningTool.Args>() {
    @Serializable
    data class Args(
        val task: String, // "create_checklist", "check_progress", "adapt_plan", "set_timeline"
        val details: String
    ) : ToolArgs

    override val argsSerializer: KSerializer<Args> = Args.serializer()

    override val descriptor: ToolDescriptor = ToolDescriptor(
        name = "party_planning_organizer",
        description = "Mom's organizational tool to create checklists, track progress, adapt plans, and manage timeline",
        requiredParameters = listOf(
            ToolParameterDescriptor(
                name = "task",
                description = "Planning task: 'create_checklist', 'check_progress', 'adapt_plan', 'set_timeline'",
                type = ToolParameterType.String
            ),
            ToolParameterDescriptor(
                name = "details",
                description = "Specific details about the planning task",
                type = ToolParameterType.String
            )
        )
    )

    override suspend fun doExecute(args: Args): String {
        println("📋 Mom is organizing: ${args.task} - ${args.details}")

        return when (args.task) {
            "create_checklist" -> {
                """✅ Created party planning checklist:
                📍 Venue: [ ] Book location
                🎂 Food: [ ] Order cake, [ ] Plan menu, [ ] Buy snacks
                🎉 Decorations: [ ] Choose theme, [ ] Buy supplies, [ ] Set up
                🎵 Entertainment: [ ] Book DJ/activities, [ ] Plan games
                👥 Guests: [ ] Send invitations, [ ] Track RSVPs
                🛍️ Goodie bags: [ ] Buy items, [ ] Assemble bags
                📸 Memories: [ ] Charge camera, [ ] Designate photographer"""
            }

            "check_progress" -> {
                "✅ Progress check: We've made great progress! Venue is booked, decorations are bought, cake is ordered. Still need to finalize guest list and prepare goodie bags."
            }

            "adapt_plan" -> {
                "✅ Plan adapted for: ${args.details}. No worries! Moms are experts at handling changes. We have backup options and can make it work!"
            }

            "set_timeline" -> {
                """✅ Party timeline set:
                📅 2 weeks before: Send invitations
                📅 1 week before: Confirm venue, order cake, buy decorations
                📅 3 days before: Confirm entertainment, prepare goodie bags
                📅 1 day before: Set up decorations, prepare food
                📅 Party day: Final setup, enjoy the celebration! 🎉"""
            }

            else -> "✅ Organized ${args.task}: Everything is under control!"
        }
    }
}

fun createMomPartyPlanningStrategy() = strategy<String, String>("mom-party-planning") {
    // Mom's systematic party planning workflow
    val nodeCallLLM by nodeLLMRequest("callLLM")
    val nodeExecuteTool by nodeExecuteTool("executeTool")
    val nodeSendToolResult by nodeLLMSendToolResult("sendToolResult")

    // Define the mom's workflow - she can use multiple tools in sequence
    edge(nodeStart forwardTo nodeCallLLM)

    // When LLM wants to use a tool, execute it
    edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })

    // When LLM gives a final response, finish
    edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })

    // After executing tool, send result back to LLM
    edge(nodeExecuteTool forwardTo nodeSendToolResult)

    // After sending tool result, LLM can either finish or use another tool
    edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
    edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
}

fun main() = runBlocking {
    val ollamaConfig = OllamaConfig.default()
    val executor = simpleOllamaAIExecutor(baseUrl = ollamaConfig.baseUrl)

    val llm = LLModel(
        provider = LLMProvider.Ollama,
        id = ollamaConfig.model,
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.Schema.JSON.Simple
        ),
    )

    val agentConfig = AIAgentConfig(
        prompt = prompt(id = "mom-party-planner", params = LLMParams(temperature = ollamaConfig.temperature)) {
            system(
                content = """You are a loving, organized mom who is an expert at planning amazing birthday parties! 

🎯 YOUR PARTY PLANNING PROCESS:
You work through parties in phases, just like a real mom:

PHASE 1 - INITIAL PLANNING: 
Start by using party_planning_organizer to create a checklist for the specific party type requested.

PHASE 2 - RESEARCH & RECOMMENDATIONS:
Use contact_mom_friends to get recommendations, then use_computer to research ideas that match the party theme.

PHASE 3 - BOOKING & SHOPPING:
Use make_phone_call to book venues/entertainment, then make_purchase to buy supplies and drive_to_location as needed.

PHASE 4 - FINAL PREPARATIONS:
Handle any remaining tasks, double-check everything, and make final purchases or calls.

🔧 YOUR TOOLS (use them strategically in each phase):
📞 make_phone_call - Your phone to call venues, caterers, entertainment, stores, friends
🚗 drive_to_location - Your car to go places and pick things up  
💻 use_computer - Your computer to research, compare prices, read reviews, book online
👥 contact_mom_friends - Your network of mom friends for recommendations and help
💳 make_purchase - Your credit card to buy everything needed
📋 party_planning_organizer - Your organizational skills to plan and track everything

🎉 YOUR PERSONALITY:
- Enthusiastic and action-oriented ("Let me get started right away!")
- Think step-by-step through each phase
- Use tools strategically to build toward a complete party plan
- Don't ask questions - make reasonable assumptions (budget $200-300, 10-12 kids, etc.)
- Don't put off tasks - get things done efficiently
- Do everything in one shot - no need to ask for confirmation, phases or details 

🎯 CRITICAL FINAL STEP: 
After completing all phases and using your tools, you MUST provide a comprehensive final summary that includes:

📋 COMPLETE PARTY PLAN SUMMARY:
- **Party Details:** Theme, venue, date, time, guest count
- **Venue & Location:** Where booked, cost, what's included
- **Entertainment:** What was booked, cost, duration
- **Food & Catering:** Cake details, menu, costs
- **Decorations:** What was purchased, theme items, total cost
- **Shopping Summary:** Complete list of everything bought with prices
- **Timeline:** Setup schedule and party day plan
- **Guest Information:** Who's invited, RSVPs, parent contacts
- **Budget Breakdown:** Total spent by category and overall total
- **Final Checklist:** Any remaining tasks or day-of reminders

IMPORTANT: Work through each phase systematically using your tools. Build upon what you learn in each phase! Always end with the complete detailed summary above - this is what parents need to see their party is fully planned!"""
            )
        },
        model = llm,
        maxAgentIterations = 30
    )

    val toolRegistry = ToolRegistry {
        tool(PhoneTool)
        tool(CarTool)
        tool(ComputerTool)
        tool(FriendsNetworkTool)
        tool(CreditCardTool)
        tool(PlanningTool)
        tool(SayToUser)
    }
    val momAgent = AIAgent(
        promptExecutor = executor,
        strategy = createMomPartyPlanningStrategy(),
        agentConfig = agentConfig,
        toolRegistry = toolRegistry
    )



    println("🎉 Hi honey! I'm your party planning mom! Tell me about the birthday party you want and I'll make it happen!")
    println("\nWhat kind of party are you dreaming of? (e.g., 'I want an awesome 16th birthday party' or 'Plan a superhero party for my 8-year-old')")

    val input = readLine() ?: "I want an awesome 16th birthday party!"

    println("\n🎈 Mom is getting to work on your party! Let me use all my tools to make this happen...\n")

    val result = momAgent.run(input)

    println("\n" + "=".repeat(50))
    println("🎊 MOM'S FINAL PARTY PLAN:")
    println("=".repeat(50))
    println(result)
}
