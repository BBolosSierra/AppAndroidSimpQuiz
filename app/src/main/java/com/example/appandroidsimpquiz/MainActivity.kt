package com.example.appandroidsimpquiz

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.appandroidsimpquiz.ui.theme.AppAndroidSimpQuizTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import kotlin.random.Random

// ---------- ENUMS ----------
enum class Difficulty { HOMER, MARGE, LISA }
enum class Mode { SINGLE, MULTI }
enum class Screen { MENU, GAME, END }

// ---------- MODELS ----------
sealed class Question {
    abstract val id: String
    abstract val difficulty: Difficulty
    abstract val prompt: String

    data class Mcq(
        override val id: String,
        override val difficulty: Difficulty,
        override val prompt: String,
        val options: List<String>,
        val correctIndex: Int
    ) : Question()

    data class Typed(
        override val id: String,
        override val difficulty: Difficulty,
        override val prompt: String,
        val accepted: List<String>
    ) : Question()
}

// ---------- JSON LOADER ----------
fun loadQuestions(context: Context): List<Question> {
    val json = context.assets.open("questions.json").bufferedReader().use { it.readText() }
    val arr = JSONArray(json)
    val out = mutableListOf<Question>()

    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val id = o.getString("id")
        val diff = Difficulty.valueOf(o.getString("difficulty"))
        val prompt = o.getString("prompt")
        val type = o.getString("type")

        if (type == "mcq") {
            val opts = o.getJSONArray("options")
            val options = (0 until opts.length()).map { opts.getString(it) }
            out.add(
                Question.Mcq(
                    id, diff, prompt,
                    options,
                    o.getInt("correctIndex")
                )
            )
        } else {
            val acc = o.getJSONArray("accepted")
            val accepted = (0 until acc.length()).map { acc.getString(it) }
            out.add(Question.Typed(id, diff, prompt, accepted))
        }
    }
    return out
}

// ---------- ANSWER CHECK ----------
private fun normalize(s: String) =
    s.lowercase().replace(Regex("[^a-z0-9]"), "")

private fun isCorrect(q: Question, answer: String): Boolean =
    when (q) {
        is Question.Mcq -> false
        is Question.Typed -> q.accepted.any { normalize(it) == normalize(answer) }
    }

// ---------- ACTIVITY ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        FirebaseAuth.getInstance().signInAnonymously()

        val allQuestions = loadQuestions(this)

        setContent {
            AppAndroidSimpQuizTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(allQuestions)
                }
            }
        }
    }
}

// ---------- ROOT ----------
@Composable
fun AppRoot(allQuestions: List<Question>) {
    var screen by remember { mutableStateOf(Screen.MENU) }
    var mode by remember { mutableStateOf(Mode.SINGLE) }
    var difficulty by remember { mutableStateOf(Difficulty.HOMER) }
    var playerName by remember { mutableStateOf("") }

    var score by remember { mutableStateOf(0) }
    var currentQuestion by remember { mutableStateOf<Question?>(null) }
    var typedInput by remember { mutableStateOf("") }

    fun nextQuestion() {
        val pool = allQuestions.filter { it.difficulty == difficulty }
        currentQuestion = pool.random()
        typedInput = ""
    }

    when (screen) {
        Screen.MENU -> {
            Column(Modifier.padding(16.dp)) {
                Text("Simpsons Quiz", style = MaterialTheme.typography.titleLarge)

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = playerName,
                    onValueChange = { playerName = it },
                    label = { Text("Your name") }
                )

                Spacer(Modifier.height(12.dp))
                Text("Difficulty")
                Difficulty.values().forEach {
                    Row {
                        RadioButton(
                            selected = it == difficulty,
                            onClick = { difficulty = it }
                        )
                        Text(it.name)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        score = 0
                        nextQuestion()
                        screen = Screen.GAME
                    },
                    enabled = playerName.isNotBlank()
                ) {
                    Text("Start Single Player")
                }
            }
        }

        Screen.GAME -> {
            val q = currentQuestion ?: return
            Column(Modifier.padding(16.dp)) {
                Text("Player: $playerName")
                Text("Score: $score")
                Spacer(Modifier.height(12.dp))
                Text(q.prompt, style = MaterialTheme.typography.titleMedium)

                when (q) {
                    is Question.Mcq -> {
                        q.options.forEachIndexed { idx, opt ->
                            Button(
                                onClick = {
                                    if (idx == q.correctIndex) score++
                                    nextQuestion()
                                },
                                modifier = Modifier.fillMaxWidth().padding(4.dp)
                            ) { Text(opt) }
                        }
                    }
                    is Question.Typed -> {
                        OutlinedTextField(
                            value = typedInput,
                            onValueChange = { typedInput = it },
                            label = { Text("Your answer") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (isCorrect(q, typedInput)) score++
                                nextQuestion()
                            })
                        )
                        Button(
                            onClick = {
                                if (isCorrect(q, typedInput)) score++
                                nextQuestion()
                            }
                        ) { Text("Submit") }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(onClick = { screen = Screen.END }) {
                    Text("End Game")
                }
            }
        }

        Screen.END -> {
            Column(Modifier.padding(16.dp)) {
                Text("Game Over", style = MaterialTheme.typography.titleLarge)
                Text("Final score: $score")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { screen = Screen.MENU }) {
                    Text("Back to menu")
                }
            }
        }
    }
}

