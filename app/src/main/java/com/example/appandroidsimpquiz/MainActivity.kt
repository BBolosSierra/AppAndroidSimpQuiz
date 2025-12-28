package com.example.appandroidsimpquiz

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.appandroidsimpquiz.ui.theme.AppAndroidSimpQuizTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import kotlin.random.Random
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.example.appandroidsimpquiz.R
import java.text.Normalizer

// ---------- Simpsons style ----------
val SimpsonsTitleFont = FontFamily(Font(R.font.simpsonfont))
val SimpsonsYellow = Color(0xFFFED41D)
val SimpsonsBlue = Color(0xFF74B9FF)
val SimpsonsRed = Color(0xFFE53935)
val SimpsonsGreen = Color(0xFF00C853)

val winnerImages = listOf(
    R.drawable.pic6_winner,
    R.drawable.pic7_winner,
    R.drawable.pic10_winner,
    R.drawable.pic12_winner,
    R.drawable.pic3_right_answer,
    R.drawable.pic17_winner,
    R.drawable.pic18_winner,
    R.drawable.pic20_winner,
    R.drawable.pic22_winner,
    R.drawable.pic23_winner,
    R.drawable.pic25_winner,


)

val loserImages = listOf(
    R.drawable.pic9_loser,
    R.drawable.pic11_loser,
    R.drawable.pic15_wrong_answer,
    R.drawable.pic16_wrong_answer,
    R.drawable.pic39_loser,
    R.drawable.pic40_loser,
    R.drawable.pic41_loser,
    R.drawable.pic42_loser,
    R.drawable.pic43_loser,
)

val undecisionImages = listOf(
    R.drawable.pic21_undecision,
    R.drawable.pic29_draw
)

@Composable
fun AppScaffoldWithBackground(
    @DrawableRes backgroundRes: Int,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = backgroundRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.45f)
                        )
                    )
                )
        )

        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            content()
        }
    }
}

// ---------- ENUMS ----------
enum class Difficulty { HOMER, MARGE, LISA, MIXED }
enum class Screen { MENU, SINGLE_GAME, MULTI_LOBBY, MULTI_GAME, END }
enum class SetupStep { NAME, DIFFICULTY, MODE, QUESTIONS_SINGLE}


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

data class RoomState(
    val code: String = "",
    val status: String = "LOBBY", // LOBBY | PLAYING | ENDED
    val difficulty: Difficulty = Difficulty.HOMER,

    val hostUid: String = "",
    val p1Uid: String = "",
    val p1Name: String = "Player 1",
    val p2Uid: String? = null,
    val p2Name: String? = null,

    val scoresP1: Int = 0,
    val scoresP2: Int = 0,

    val turnUid: String = "",
    val currentQuestionId: String? = null,
    val usedQuestionIds: Set<String> = emptySet(),
    val maxQuestions: Int = 10       // <- NEW
)


// ---------- ANSWER CHECK ----------
private fun normalizers(s: String): String {
    val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")

    return noAccents
        .lowercase()
        .trim()
        .replace("[^a-z0-9\\s]".toRegex(), "")
        .replace("\\s+".toRegex(), " ")
}

private fun typedCorrectanswer(q: Question.Typed, user: String): Boolean {
    val u = normalizers(user)
    return q.accepted.any { normalizers(it) == u }
}

// ---------- JSON LOADER ----------
fun loadQuestions(context: Context): List<Question> {
    val json = context.assets.open("questions.json").bufferedReader().use { it.readText() }
    val arr = JSONArray(json)
    val out = mutableListOf<Question>()

    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val id = o.getString("id")
        val diff = Difficulty.valueOf(o.getString("difficulty").trim().uppercase())
        val prompt = o.getString("prompt")
        val type = o.getString("type").trim().lowercase()

        when (type) {
            "mcq" -> {
                val opts = o.getJSONArray("options")
                val options = (0 until opts.length()).map { opts.getString(it) }
                out.add(
                    Question.Mcq(
                        id = id,
                        difficulty = diff,
                        prompt = prompt,
                        options = options,
                        correctIndex = o.getInt("correctIndex")
                    )
                )
            }
            "typed" -> {
                val acc = o.getJSONArray("accepted")
                val accepted = (0 until acc.length()).map { acc.getString(it) }
                out.add(
                    Question.Typed(
                        id = id,
                        difficulty = diff,
                        prompt = prompt,
                        accepted = accepted
                    )
                )
            }
            else -> error("Unknown type '$type' for id=$id")
        }
    }
    return out
}

// ---------- ANSWER CHECK ----------
private fun normalize(s: String) =
    s.trim().lowercase()
        .replace(Regex("[^a-z0-9\\s]"), "")
        .replace(Regex("\\s+"), " ")

private fun typedCorrect(q: Question.Typed, user: String): Boolean {
    val u = normalize(user)
    return q.accepted.any { normalize(it) == u }
}

// ---------- MULTIPLAYER HELPERS ----------
private fun generateRoomCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..6).map { chars.random() }.joinToString("")
}

private fun pickNextQuestionId(
    allQuestions: List<Question>,
    diff: Difficulty,
    used: Set<String>
): String? {
    val pool = when (diff) {
        Difficulty.MIXED ->
            allQuestions.filter { it.id !in used }
        else ->
            allQuestions.filter { it.difficulty == diff && it.id !in used }
    }
    if (pool.isEmpty()) return null
    return pool.random().id
}

// ---------- FIRESTORE OPERATIONS ----------
private fun createRoom(
    db: FirebaseFirestore,
    uid: String,
    name: String,
    diff: Difficulty,
    maxQuestions: Int,
    onDone: (String) -> Unit,
    onFail: (String) -> Unit
) {
    val code = generateRoomCode()
    val doc = db.collection("rooms").document(code)
    val data = mapOf(
        "status" to "LOBBY",
        "difficulty" to diff.name,

        "hostUid" to uid,
        "p1Uid" to uid,
        "p1Name" to name,
        "p2Uid" to null,
        "p2Name" to null,

        "scoresP1" to 0,
        "scoresP2" to 0,

        "turnUid" to uid,
        "currentQuestionId" to null,
        "usedQuestionIds" to emptyList<String>(),
        "maxQuestions" to maxQuestions          // <- NEW
    )
    doc.set(data)
        .addOnSuccessListener { onDone(code) }
        .addOnFailureListener { onFail(it.message ?: "Create room failed") }
}

private fun joinRoom(
    db: FirebaseFirestore,
    code: String,
    uid: String,
    name: String,
    onOk: () -> Unit,
    onFail: (String) -> Unit
) {
    val rid = code.trim().uppercase()
    val doc = db.collection("rooms").document(rid)

    db.runTransaction { tx ->
        val snap = tx.get(doc)
        if (!snap.exists()) throw IllegalStateException("Room not found")

        val p1 = snap.getString("p1Uid")
        val p2 = snap.getString("p2Uid")

        when {
            p1 == uid || p2 == uid -> Unit
            p2.isNullOrBlank() -> tx.update(doc, mapOf("p2Uid" to uid, "p2Name" to name))
            else -> throw IllegalStateException("Room is full")
        }
        null
    }.addOnSuccessListener { onOk() }
        .addOnFailureListener { onFail(it.message ?: "Join failed") }
}

private fun hostStartGame(
    db: FirebaseFirestore,
    allQuestions: List<Question>,
    code: String,
    hostUid: String,
    onFail: (String) -> Unit
) {
    val doc = db.collection("rooms").document(code)
    db.runTransaction { tx ->
        val snap = tx.get(doc)
        val status = snap.getString("status") ?: "LOBBY"
        val host = snap.getString("hostUid") ?: ""
        val p2 = snap.getString("p2Uid")

        if (host != hostUid) return@runTransaction null
        if (status != "LOBBY") return@runTransaction null
        if (p2.isNullOrBlank()) throw IllegalStateException("Waiting for Player 2")

        val diff = Difficulty.valueOf((snap.getString("difficulty") ?: "HOMER").uppercase())
        val used =
            (snap.get("usedQuestionIds") as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        val qid = pickNextQuestionId(allQuestions, diff, used)
            ?: throw IllegalStateException("No questions for this difficulty")

        tx.update(
            doc, mapOf(
                "status" to "PLAYING",
                "scoresP1" to 0,
                "scoresP2" to 0,
                "turnUid" to snap.getString("p1Uid"),
                "currentQuestionId" to qid,
                "usedQuestionIds" to used.plus(qid).toList()
            )
        )
        null
    }.addOnFailureListener { onFail(it.message ?: "Start failed") }
}

private fun submitAnswer(
    db: FirebaseFirestore,
    code: String,
    uid: String,
    correct: Boolean,
    allQuestions: List<Question>,
    onFail: (String) -> Unit
) {
    val doc = db.collection("rooms").document(code)
    db.runTransaction { tx ->
        val snap = tx.get(doc)
        val status = snap.getString("status") ?: "LOBBY"
        if (status != "PLAYING") return@runTransaction null

        val turnUid = snap.getString("turnUid")
        if (turnUid != uid) return@runTransaction null

        val p1 = snap.getString("p1Uid") ?: ""
        val p2 = snap.getString("p2Uid") ?: ""

        val s1 = (snap.getLong("scoresP1") ?: 0L).toInt()
        val s2 = (snap.getLong("scoresP2") ?: 0L).toInt()

        val newS1 = if (correct && uid == p1) s1 + 1 else s1
        val newS2 = if (correct && uid == p2) s2 + 1 else s2

        val diff = Difficulty.valueOf((snap.getString("difficulty") ?: "HOMER").uppercase())
        val used =
            (snap.get("usedQuestionIds") as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        val maxQuestions = (snap.getLong("maxQuestions") ?: 10L).toInt()

        if (used.size >= maxQuestions) {
            tx.update(
                doc, mapOf(
                    "scoresP1" to newS1,
                    "scoresP2" to newS2,
                    "status" to "ENDED"
                )
            )
            return@runTransaction null
        }

        val nextTurn = if (uid == p1) p2 else p1
        val nextQid = pickNextQuestionId(allQuestions, diff, used)

        if (nextQid == null) {
            tx.update(
                doc, mapOf(
                    "scoresP1" to newS1,
                    "scoresP2" to newS2,
                    "status" to "ENDED"
                )
            )
            return@runTransaction null
        }

        tx.update(
            doc, mapOf(
                "scoresP1" to newS1,
                "scoresP2" to newS2,
                "turnUid" to nextTurn,
                "currentQuestionId" to nextQid,
                "usedQuestionIds" to used.plus(nextQid).toList()
            )
        )
        null
    }.addOnFailureListener { onFail(it.message ?: "Submit failed") }
}

// ---------- NEW: Setup screens ----------
@Composable
private fun NameStep(
    name: String,
    onNameChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = "Simpsons Quiz",
                fontFamily = SimpsonsTitleFont,
                style = MaterialTheme.typography.displayLarge,
                color = SimpsonsYellow
            )
            Spacer(Modifier.height(50.dp))

            Text(
                text = "Tu app para demostrar... ¿cuánto sabes de los Simpson?",
                fontFamily = SimpsonsTitleFont,
                style = MaterialTheme.typography.titleMedium,
                color = SimpsonsYellow
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = {
                    Text(
                        "Name",
                        color = SimpsonsYellow,
                        fontFamily = SimpsonsTitleFont,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = SimpsonsTitleFont,
                    color = SimpsonsYellow
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SimpsonsYellow,
                    unfocusedTextColor = SimpsonsYellow,
                    focusedLabelColor = SimpsonsYellow,
                    unfocusedLabelColor = SimpsonsYellow,
                    focusedBorderColor = SimpsonsYellow,
                    unfocusedBorderColor = SimpsonsYellow.copy(alpha = 0.95f),
                    cursorColor = SimpsonsYellow,
                    focusedContainerColor = Color.Black.copy(alpha = 0.55f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.45f)
                )
            )
        }

        FloatingActionButton(
            onClick = onContinue,
            containerColor = SimpsonsYellow,
            contentColor = Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Text(
                "Next",
                fontFamily = SimpsonsTitleFont
            )
        }
    }
}

@Composable
private fun DifficultyStep(
    difficulty: Difficulty,
    onDifficultyChange: (Difficulty) -> Unit,
    onContinue: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Difficulty",
                color = SimpsonsYellow,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = SimpsonsTitleFont
            )
            Spacer(Modifier.height(16.dp))

            Difficulty.values().forEach { d ->
                val selected = (d == difficulty)

                Button(
                    onClick = { onDifficultyChange(d) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) SimpsonsYellow else Color.Black.copy(alpha = 0.55f),
                        contentColor = if (selected) Color.Black else SimpsonsYellow
                    )
                ) {
                    val label = when (d) {
                        Difficulty.HOMER -> "Homer "
                        Difficulty.MARGE -> "Marge"
                        Difficulty.LISA -> "Lisa"
                        Difficulty.MIXED -> "Mix"
                    }
                    Text(
                        text = label,
                        fontFamily = SimpsonsTitleFont,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onContinue,
            containerColor = SimpsonsYellow,
            contentColor = Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Text(
                "Next",
                fontFamily = SimpsonsTitleFont
            )
        }
    }
}

@Composable
private fun ModeStep(
    onSingle: () -> Unit,
    onMulti: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Elige modo",
                color = SimpsonsYellow,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = SimpsonsTitleFont
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onSingle,
                colors = ButtonDefaults.buttonColors(containerColor = SimpsonsYellow),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Juego solo", color = Color.Black, fontFamily = SimpsonsTitleFont) }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onMulti,
                colors = ButtonDefaults.buttonColors(containerColor = SimpsonsYellow),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Te reto a un duelo (2 móviles)", color = Color.Black, fontFamily = SimpsonsTitleFont) }
        }
    }
}

@Composable
private fun QuestionsSingleStep(
    difficulty: Difficulty,
    onDifficultyChange: (Difficulty) -> Unit,
    questionsInput: String,
    onQuestionsChange: (String) -> Unit,
    onStartGame: (Int) -> Unit
) {
    val n = questionsInput.toIntOrNull()
    val valid = n != null && n in 1..51

    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "¿Cuántas preguntas quieres?",
                color = SimpsonsYellow,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = SimpsonsTitleFont
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = questionsInput,
                onValueChange = { text ->
                    val filtered = text.filter { it.isDigit() }.take(2)
                    onQuestionsChange(filtered)
                },
                label = { Text("Número de preguntas (1-51)", color = SimpsonsYellow, fontFamily = SimpsonsTitleFont) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SimpsonsYellow,
                    unfocusedTextColor = SimpsonsYellow,
                    focusedLabelColor = SimpsonsYellow,
                    unfocusedLabelColor = SimpsonsYellow,
                    focusedBorderColor = SimpsonsYellow,
                    unfocusedBorderColor = SimpsonsYellow.copy(alpha = 0.7f),
                    cursorColor = SimpsonsYellow
                )
            )

            if (!valid && questionsInput.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Escribe un número entre 1 y 51",
                    color = SimpsonsRed,
                    fontFamily = SimpsonsTitleFont
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "DIFFICULTY",
                color = SimpsonsYellow,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = SimpsonsTitleFont
            )
            Spacer(Modifier.height(8.dp))

            Difficulty.values().forEach { d ->
                val selected = (d == difficulty)
                Button(
                    onClick = { onDifficultyChange(d) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) SimpsonsYellow else Color.Black.copy(alpha = 0.55f),
                        contentColor = if (selected) Color.Black else SimpsonsYellow
                    )
                ) {
                    val label = when (d) {
                        Difficulty.HOMER -> "HOMER"
                        Difficulty.MARGE -> "MARGE"
                        Difficulty.LISA  -> "LISA"
                        Difficulty.MIXED -> "MIXED"
                    }
                    Text(label, fontFamily = SimpsonsTitleFont)
                }
            }
        }

        FloatingActionButton(
            onClick = { if (valid) onStartGame(n!!) },
            containerColor = SimpsonsYellow,
            contentColor = Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Text("Jugar", fontFamily = SimpsonsTitleFont)
        }
    }
}

private fun updateMaxQuestions(
    db: FirebaseFirestore,
    code: String,
    maxQuestions: Int,
    onFail: (String) -> Unit
) {
    db.collection("rooms").document(code)
        .update("maxQuestions", maxQuestions)
        .addOnFailureListener { onFail(it.message ?: "Update questions failed") }
}

private fun updateDifficulty(
    db: FirebaseFirestore,
    code: String,
    difficulty: Difficulty,
    onFail: (String) -> Unit
) {
    db.collection("rooms").document(code)
        .update("difficulty", difficulty.name)
        .addOnFailureListener { onFail(it.message ?: "Update difficulty failed") }
}


// ---------- ACTIVITY ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        val allQuestions = loadQuestions(this)

        setContent {
            AppAndroidSimpQuizTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    AppRoot(allQuestions)
                }
            }
        }
    }
}

private fun pickRandomQuestionForDifficulty(
    allQuestions: List<Question>,
    difficulty: Difficulty
): Question? {
    val pool = when (difficulty) {
        Difficulty.MIXED ->
            allQuestions
        else ->
            allQuestions.filter { it.difficulty == difficulty }
    }
    return pool.randomOrNull()
}


// ---------- ROOT ----------
@Composable
fun AppRoot(allQuestions: List<Question>) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    var uidState by remember { mutableStateOf(auth.currentUser?.uid) }
    var authError by remember { mutableStateOf<String?>(null) }


    DisposableEffect(Unit) {
        val l = FirebaseAuth.AuthStateListener { fa ->
            uidState = fa.currentUser?.uid
        }
        auth.addAuthStateListener(l)
        onDispose { auth.removeAuthStateListener(l) }
    }

    LaunchedEffect(uidState) {
        if (uidState == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { Log.d("AUTH", "Anonymous sign-in SUCCESS: ${it.user?.uid}") }
                .addOnFailureListener { e ->
                    Log.e("AUTH", "Anonymous sign-in FAILED", e)
                    authError = e.message ?: "Sign-in failed"
                }
        }
    }

    if (uidState == null) {
        Column(Modifier.padding(16.dp)) {
            Text("Signing in...", color = SimpsonsYellow)
            authError?.let { Text(it, color = SimpsonsRed) }
        }
        return
    }
    val uid = uidState!!

    // ---------- UI STATE ----------
    var screen by remember { mutableStateOf(Screen.MENU) }
    var setupStep by remember { mutableStateOf(SetupStep.NAME) }

    var difficulty by remember { mutableStateOf(Difficulty.HOMER) }
    var name by remember { mutableStateOf("") }

    // Single player
    var singleScore by remember { mutableStateOf(0) }
    var singleQuestion by remember { mutableStateOf<Question?>(null) }
    var singleTypedInput by remember { mutableStateOf("") }

    var singleAnswered by remember { mutableStateOf(false) }
    var singleFeedback by remember { mutableStateOf<String?>(null) }
    var singleCorrectAnswer by remember { mutableStateOf<String?>(null) }
    var singleTotalQuestions by remember { mutableStateOf(10) }
    var singleQuestionsAnswered by remember { mutableStateOf(0) }
    var singleQuestionsInput by remember { mutableStateOf("10") }

    // Multiplayer
    var roomCodeInput by remember { mutableStateOf("") }
    var roomCode by remember { mutableStateOf<String?>(null) }
    var roomState by remember { mutableStateOf<RoomState?>(null) }
    var mpTypedInput by remember { mutableStateOf("") }
    var mpFeedback by remember { mutableStateOf<String?>(null) }
    var mpError by remember { mutableStateOf<String?>(null) }
    var roomListener by remember { mutableStateOf<ListenerRegistration?>(null) }
    var mpCorrectAnswer by remember { mutableStateOf<String?>(null) }
    var mpQuestionsInput by remember { mutableStateOf("10") }


    fun startSingle(totalQuestions: Int) {
        singleTotalQuestions = totalQuestions
        singleQuestionsAnswered = 0
        singleScore = 0
        singleQuestion = pickRandomQuestionForDifficulty(allQuestions, difficulty)
        singleTypedInput = ""
        singleAnswered = false
        singleFeedback = null
        singleCorrectAnswer = null
        screen = Screen.SINGLE_GAME
    }

    fun nextSingle() {
        singleQuestion = pickRandomQuestionForDifficulty(allQuestions, difficulty)
        singleTypedInput = ""
        singleAnswered = false
        singleFeedback = null
        singleCorrectAnswer = null
    }

    fun resetToMenu() {
        screen = Screen.MENU
        setupStep = SetupStep.NAME
        mpError = null
        mpFeedback = null
        mpTypedInput = ""
        roomState = null
        roomCode = null
        roomCodeInput = ""
        singleQuestionsAnswered = 0
        singleTotalQuestions = 10
        singleQuestionsInput = "10"
        mpQuestionsInput = "10"
    }

    fun detachRoomListener() {
        roomListener?.remove()
        roomListener = null
    }

    fun attachRoomListener(code: String) {
        detachRoomListener()
        roomListener = db.collection("rooms").document(code)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    mpError = err.message ?: "Listener error"
                    return@addSnapshotListener
                }
                val d = snap?.data ?: return@addSnapshotListener

                val status = (d["status"] as? String) ?: "LOBBY"
                val diff =
                    Difficulty.valueOf(((d["difficulty"] as? String) ?: "HOMER").uppercase())

                val hostUid = (d["hostUid"] as? String) ?: ""
                val p1Uid = (d["p1Uid"] as? String) ?: ""
                val p1Name = (d["p1Name"] as? String) ?: "Player 1"
                val p2Uid = d["p2Uid"] as? String
                val p2Name = d["p2Name"] as? String

                val s1 = ((d["scoresP1"] as? Long) ?: 0L).toInt()
                val s2 = ((d["scoresP2"] as? Long) ?: 0L).toInt()

                val turn = (d["turnUid"] as? String) ?: ""
                val qid = d["currentQuestionId"] as? String
                val used =
                    (d["usedQuestionIds"] as? List<*>)?.filterIsInstance<String>()?.toSet()
                        ?: emptySet()
                val maxQuestions = ((d["maxQuestions"] as? Long) ?: 10L).toInt()

                roomState = RoomState(
                    code = code,
                    status = status,
                    difficulty = diff,
                    hostUid = hostUid,
                    p1Uid = p1Uid,
                    p1Name = p1Name,
                    p2Uid = p2Uid,
                    p2Name = p2Name,
                    scoresP1 = s1,
                    scoresP2 = s2,
                    turnUid = turn,
                    currentQuestionId = qid,
                    usedQuestionIds = used,
                    maxQuestions = maxQuestions
                )

                screen = when (status) {
                    "PLAYING" -> Screen.MULTI_GAME
                    "ENDED" -> Screen.END
                    else -> Screen.MULTI_LOBBY
                }
            }
    }

    DisposableEffect(Unit) {
        onDispose { detachRoomListener() }
    }

    // ---------- setup flow ----------
    if (screen == Screen.MENU) {
        when (setupStep) {
            SetupStep.NAME -> {
                AppScaffoldWithBackground(backgroundRes = R.drawable.pic1) {
                    NameStep(
                        name = name,
                        onNameChange = { name = it },
                        onContinue = { if (name.isNotBlank()) setupStep = SetupStep.MODE }
                    )
                }
                return
            }

            SetupStep.DIFFICULTY -> {
                AppScaffoldWithBackground(backgroundRes = R.drawable.pic34_dark) {
                    DifficultyStep(
                        difficulty = difficulty,
                        onDifficultyChange = { difficulty = it },
                        onContinue = { setupStep = SetupStep.MODE }
                    )
                }
                return
            }

            SetupStep.MODE -> {
                AppScaffoldWithBackground(backgroundRes = R.drawable.pic35_dark) {
                    ModeStep(
                        onSingle = { setupStep = SetupStep.QUESTIONS_SINGLE },
                        onMulti = {
                            mpError = null
                            mpFeedback = null
                            mpTypedInput = ""
                            roomCode = null
                            roomState = null
                            screen = Screen.MULTI_LOBBY
                        }
                    )
                }
                return
            }

            SetupStep.QUESTIONS_SINGLE -> {
                AppScaffoldWithBackground(backgroundRes = R.drawable.pic35_dark) {
                    QuestionsSingleStep(
                        difficulty = difficulty,
                        onDifficultyChange = { difficulty = it },
                        questionsInput = singleQuestionsInput,
                        onQuestionsChange = { singleQuestionsInput = it },
                        onStartGame = { n ->
                            singleTotalQuestions = n
                            startSingle(n)
                        }
                    )
                }
                return
            }
        }
    }

    // ---------- EXISTING SCREENS ----------
    when (screen) {
        Screen.SINGLE_GAME -> {
            AppScaffoldWithBackground(backgroundRes = R.drawable.pic35_dark) {
                val q = singleQuestion
                if (q == null) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "No hay preguntas para ${difficulty.name}.",
                            color = SimpsonsYellow,
                            fontFamily = SimpsonsTitleFont
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { resetToMenu() }) { Text("Volver", fontFamily = SimpsonsTitleFont) }
                    }
                    return@AppScaffoldWithBackground
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Cabecera
                    Text(
                        text = "Jugador: $name",
                        color = SimpsonsYellow,
                        fontFamily = SimpsonsTitleFont,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Puntuación: $singleScore",
                        color = SimpsonsBlue,
                        fontFamily = SimpsonsTitleFont,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(Modifier.height(16.dp))
                    // Progreso preguntas (single)
                    val currentSingleIndex =
                        (if (singleAnswered) singleQuestionsAnswered else singleQuestionsAnswered + 1)
                            .coerceAtMost(singleTotalQuestions)
                    val singleRemaining =
                        (singleTotalQuestions - currentSingleIndex).coerceAtLeast(0)

                    Text(
                        text = "Pregunta $currentSingleIndex de $singleTotalQuestions (quedan $singleRemaining)",
                        color = SimpsonsYellow,
                        fontFamily = SimpsonsTitleFont,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(Modifier.height(16.dp))

                    // Enunciado de la pregunta
                    Text(
                        text = q.prompt,
                        color = SimpsonsYellow,
                        fontFamily = SimpsonsTitleFont,
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Spacer(Modifier.height(16.dp))

                    // Si todavía no ha respondido, mostramos las opciones / campo de texto
                    if (!singleAnswered) {
                        when (q) {
                            is Question.Mcq -> {
                                q.options.forEachIndexed { idx, opt ->
                                    Button(
                                        onClick = {
                                            val correct = idx == q.correctIndex
                                            if (correct) singleScore++

                                            singleQuestionsAnswered++
                                            singleAnswered = true
                                            singleFeedback = if (correct) "¡Correcto!" else "¡Error!"
                                            singleCorrectAnswer = q.options[q.correctIndex]
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SimpsonsYellow,
                                            contentColor = Color.Black
                                        )
                                    ) {
                                        Text(opt, fontFamily = SimpsonsTitleFont)
                                    }
                                }
                            }

                            is Question.Typed -> {
                                OutlinedTextField(
                                    value = singleTypedInput,
                                    onValueChange = { singleTypedInput = it },
                                    label = {
                                        Text(
                                            "Tu respuesta",
                                            color = SimpsonsYellow,
                                            fontFamily = SimpsonsTitleFont
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val correct = typedCorrect(q, singleTypedInput)
                                        if (correct) singleScore++

                                        singleQuestionsAnswered++
                                        singleAnswered = true
                                        singleFeedback = if (correct) "¡Correcto!" else "¡Error!"
                                        singleCorrectAnswer = q.accepted.firstOrNull()
                                    }),
                                    textStyle = LocalTextStyle.current.copy(
                                        fontFamily = SimpsonsTitleFont,
                                        color = SimpsonsYellow
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = SimpsonsYellow,
                                        unfocusedTextColor = SimpsonsYellow,
                                        focusedLabelColor = SimpsonsYellow,
                                        unfocusedLabelColor = SimpsonsYellow,
                                        focusedBorderColor = SimpsonsYellow,
                                        unfocusedBorderColor = SimpsonsYellow.copy(alpha = 0.7f),
                                        cursorColor = SimpsonsYellow,
                                        focusedContainerColor = Color.Black.copy(alpha = 0.55f),
                                        unfocusedContainerColor = Color.Black.copy(alpha = 0.45f)
                                    )
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val correct = typedCorrect(q, singleTypedInput)
                                        if (correct) singleScore++

                                        singleQuestionsAnswered++
                                        singleAnswered = true
                                        singleFeedback = if (correct) "¡Correcto!" else "¡Error!"
                                        singleCorrectAnswer = q.accepted.firstOrNull()
                                    },
                                    enabled = singleTypedInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SimpsonsYellow,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text("Responder", fontFamily = SimpsonsTitleFont)
                                }
                            }
                        }
                    } else {
                        // Ya ha respondido: mostramos feedback + respuesta correcta
                        singleFeedback?.let {
                            Text(
                                text = it,
                                color = if (it.contains("Correcto")) SimpsonsBlue else SimpsonsRed,
                                fontFamily = SimpsonsTitleFont,
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        singleCorrectAnswer?.let { ans ->
                            Text(
                                text = "Respuesta correcta:",
                                color = SimpsonsYellow,
                                fontFamily = SimpsonsTitleFont,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = ans,
                                color = SimpsonsGreen,
                                fontFamily = SimpsonsTitleFont,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        if (singleQuestionsAnswered >= singleTotalQuestions) {
                            Button(
                                onClick = { screen = Screen.END },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SimpsonsYellow,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("Ver resultados", fontFamily = SimpsonsTitleFont)
                            }
                        } else {
                            Button(
                                onClick = { nextSingle() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SimpsonsYellow,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("Siguiente pregunta", fontFamily = SimpsonsTitleFont)
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Botón “Me rindo” abajo a la izquierda
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        TextButton(onClick = { resetToMenu() }) {
                            Text(
                                "Leave",
                                color = SimpsonsYellow,
                                fontFamily = SimpsonsTitleFont
                            )
                        }
                    }
                }
            }
        }

        Screen.MULTI_LOBBY -> {
            AppScaffoldWithBackground(backgroundRes = R.drawable.pic35_dark) {
                val rs = roomState
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Multiplayer Lobby",
                        style = MaterialTheme.typography.titleLarge,
                        color = SimpsonsYellow,
                        fontFamily = SimpsonsTitleFont
                    )
                    Spacer(Modifier.height(12.dp))

                    mpError?.let {
                        Text(it, color = SimpsonsRed)
                        Spacer(Modifier.height(8.dp))
                    }

                    if (roomCode == null) {
                        // FIRST PAGE: only create / join, no nº preguntas here

                        Button(
                            onClick = {
                                Log.e("MP", "Create room clicked. uid=$uid name=$name diff=$difficulty")
                                mpError = null
                                createRoom(
                                    db = db,
                                    uid = uid,
                                    name = name,
                                    diff = difficulty,
                                    maxQuestions = 10,                 // default; host will adjust later
                                    onDone = { code ->
                                        Log.e("MP", "Room created: $code")
                                        roomCode = code
                                        roomCodeInput = code
                                        attachRoomListener(code)
                                    },
                                    onFail = { err ->
                                        Log.e("MP", "Create room failed: $err")
                                        mpError = err
                                    }
                                )
                            },
                            enabled = name.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Crear sala (host)") }

                        Spacer(Modifier.height(16.dp))

                        // Only room code + join for the second player
                        OutlinedTextField(
                            value = roomCodeInput,
                            onValueChange = { roomCodeInput = it.uppercase() },
                            label = { Text("Código sala", color = SimpsonsYellow) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SimpsonsYellow,
                                unfocusedTextColor = SimpsonsYellow,
                                focusedLabelColor = SimpsonsYellow,
                                unfocusedLabelColor = SimpsonsYellow,
                                focusedBorderColor = SimpsonsYellow,
                                unfocusedBorderColor = SimpsonsYellow.copy(alpha = 0.7f),
                                cursorColor = SimpsonsYellow
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                mpError = null
                                val code = roomCodeInput.trim().uppercase()
                                joinRoom(
                                    db = db,
                                    code = code,
                                    uid = uid,
                                    name = name,
                                    onOk = {
                                        roomCode = code
                                        attachRoomListener(code)
                                    },
                                    onFail = { mpError = it }
                                )
                            },
                            enabled = name.isNotBlank() && roomCodeInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Unirse a sala") }

                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { resetToMenu() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Back to menu")
                        }
                        return@Column
                    }


                    val code = roomCode!!
                    Text("Room: $code", color = SimpsonsYellow)
                    Spacer(Modifier.height(8.dp))

                    if (rs == null) {
                        Text("Loading room...", color = SimpsonsYellow)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { resetToMenu() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Back") }
                        return@Column
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Player 1: ${rs.p1Name}", color = SimpsonsBlue, fontFamily = SimpsonsTitleFont)
                    Text("Player 2: ${rs.p2Name ?: "(waiting)"}", color = SimpsonsBlue, fontFamily = SimpsonsTitleFont)

                    Spacer(Modifier.height(8.dp))

                    LaunchedEffect(rs.code) {
                        mpQuestionsInput = rs.maxQuestions.toString()
                    }

                    val iAmHost = (uid == rs.hostUid)
                    val mpQuestionsInt = mpQuestionsInput.toIntOrNull()
                    val mpQuestionsValid = mpQuestionsInt != null && mpQuestionsInt in 1..51

                    if (iAmHost) {
                        OutlinedTextField(
                            value = mpQuestionsInput,
                            onValueChange = { text ->
                                val filtered = text.filter { it.isDigit() }.take(2)
                                mpQuestionsInput = filtered
                            },
                            label = { Text("Nº preguntas (1-51)", color = SimpsonsYellow) },
                            singleLine = true,
                            enabled = iAmHost,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SimpsonsYellow,
                                unfocusedTextColor = SimpsonsYellow,
                                focusedLabelColor = SimpsonsYellow,
                                unfocusedLabelColor = SimpsonsYellow,
                                focusedBorderColor = SimpsonsYellow,
                                unfocusedBorderColor = SimpsonsYellow.copy(alpha = 0.7f),
                                cursorColor = SimpsonsYellow
                            )
                        )
                        Spacer(Modifier.height(16.dp))

                        Text(
                            "Difficulty",
                            color = SimpsonsYellow,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = SimpsonsTitleFont
                        )
                        Spacer(Modifier.height(8.dp))

                        Difficulty.values().forEach { d ->
                            val selected = (d == rs.difficulty)
                            Button(
                                onClick = {
                                    if (iAmHost) {
                                        updateDifficulty(
                                            db = db,
                                            code = code,
                                            difficulty = d,
                                            onFail = { mpError = it }
                                        )
                                    }
                                },
                                enabled = iAmHost,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) SimpsonsYellow else Color.Black.copy(
                                        alpha = 0.55f
                                    ),
                                    contentColor = if (selected) Color.Black else SimpsonsYellow
                                )
                            ) {
                                Text(
                                    text = d.name,
                                    fontFamily = SimpsonsTitleFont
                                )
                            }
                        }
                    }
                    if (iAmHost && !mpQuestionsValid && mpQuestionsInput.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Escribe un número entre 1 y 51",
                            color = SimpsonsRed,
                            fontFamily = SimpsonsTitleFont
                        )
                    }

                    if (iAmHost && !mpQuestionsValid && mpQuestionsInput.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Escribe un número entre 1 y 51",
                            color = SimpsonsRed,
                            fontFamily = SimpsonsTitleFont
                        )
                    }

                    if (iAmHost) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                mpError = null
                                val qNum = (mpQuestionsInt ?: rs.maxQuestions).coerceIn(1, 51)
                                updateMaxQuestions(
                                    db = db,
                                    code = code,
                                    maxQuestions = qNum,
                                    onFail = { mpError = it }
                                )
                                hostStartGame(
                                    db = db,
                                    allQuestions = allQuestions,
                                    code = code,
                                    hostUid = uid,
                                    onFail = { mpError = it }
                                )
                            },
                            enabled = !rs.p2Uid.isNullOrBlank() && mpQuestionsValid,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Start game") }
                    } else {
                        Text("Waiting for host to start.", color = SimpsonsGreen, fontFamily = SimpsonsTitleFont)
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            detachRoomListener()
                            roomState = null
                            roomCode = null
                            roomCodeInput = ""
                            mpError = null
                            mpFeedback = null
                            mpTypedInput = ""
                            resetToMenu()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Leave") }
                }
            }
        }

        Screen.MULTI_GAME -> {
            AppScaffoldWithBackground(backgroundRes = R.drawable.pic37_dark) {
                val rs = roomState
                if (rs == null) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Loading game...", color = SimpsonsYellow, fontFamily = SimpsonsTitleFont)
                    }
                    return@AppScaffoldWithBackground
                }

                val qid = rs.currentQuestionId
                val q = qid?.let { id -> allQuestions.firstOrNull { it.id == id } }

                Column(Modifier.padding(16.dp)) {
                    Text("Room: ${rs.code}", color = SimpsonsYellow, fontFamily = SimpsonsTitleFont)
                    Spacer(Modifier.height(8.dp))
                    Text("Difficulty: ${rs.difficulty.name}", color = SimpsonsBlue, fontFamily = SimpsonsTitleFont)
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Score: ${rs.p1Name} ${rs.scoresP1}  |  ${rs.p2Name ?: "Player 2"} ${rs.scoresP2}",
                        color = SimpsonsYellow,
                        fontFamily = SimpsonsTitleFont
                    )
                    Spacer(Modifier.height(8.dp))
                    // Progreso preguntas (multi)
                    val questionsPlayed = rs.usedQuestionIds.size
                    val currentQuestionIndex =
                        questionsPlayed.coerceAtMost(rs.maxQuestions)
                    val remainingQuestions =
                        (rs.maxQuestions - currentQuestionIndex).coerceAtLeast(0)

                    Text(
                        text = "Pregunta $currentQuestionIndex de ${rs.maxQuestions} (quedan $remainingQuestions)",
                        color = SimpsonsYellow,
                        fontFamily = SimpsonsTitleFont,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(Modifier.height(8.dp))

                    val currentTurnName =
                        if (rs.turnUid == rs.p1Uid) rs.p1Name else (rs.p2Name ?: "Player 2")
                    Text(
                        "Turn: $currentTurnName",
                        style = MaterialTheme.typography.titleMedium,
                        color = SimpsonsBlue,
                        fontFamily = SimpsonsTitleFont
                    )

                    Spacer(Modifier.height(12.dp))

                    // LOCAL feedback + correct answer (only on THIS phone)
                    mpFeedback?.let { fb ->
                        Text(
                            text = fb,
                            color = if (fb.contains("Correcto")) SimpsonsBlue else SimpsonsRed,
                            fontFamily = SimpsonsTitleFont,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    mpCorrectAnswer?.let { ans ->
                        Text(
                            text = "Respuesta correcta:",
                            color = SimpsonsYellow,
                            fontFamily = SimpsonsTitleFont,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = ans,
                            color = SimpsonsGreen,
                            fontFamily = SimpsonsTitleFont,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    mpError?.let {
                        Text(it, color = SimpsonsRed, fontFamily = SimpsonsTitleFont)
                        Spacer(Modifier.height(8.dp))
                    }

                    if (q == null) {
                        Text("Waiting for next question...", color = SimpsonsYellow, fontFamily = SimpsonsTitleFont)
                        return@Column
                    }

                    Text(q.prompt, style = MaterialTheme.typography.titleMedium, color = SimpsonsYellow, fontFamily = SimpsonsTitleFont)
                    Spacer(Modifier.height(12.dp))

                    val myTurn = (uid == rs.turnUid)

                    when (q) {
                        is Question.Mcq -> {
                            q.options.forEachIndexed { idx, opt ->
                                Button(
                                    onClick = {
                                        if (!myTurn) return@Button
                                        mpError = null
                                        mpFeedback = null

                                        val correct = idx == q.correctIndex
                                        mpFeedback = if (correct) "¡Correcto!" else "¡Error!"
                                        mpCorrectAnswer = q.options[q.correctIndex]

                                        submitAnswer(
                                            db = db,
                                            code = rs.code,
                                            uid = uid,
                                            correct = correct,
                                            allQuestions = allQuestions,
                                            onFail = { mpError = it }
                                        )
                                    },
                                    enabled = myTurn,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(opt, fontFamily = SimpsonsTitleFont)
                                }
                            }
                        }

                        is Question.Typed -> {
                            OutlinedTextField(
                                value = mpTypedInput,
                                onValueChange = { mpTypedInput = it },
                                label = { Text("Your answer", color = SimpsonsYellow, fontFamily = SimpsonsTitleFont) },
                                singleLine = true,
                                enabled = myTurn,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (!myTurn) return@KeyboardActions
                                    mpError = null

                                    val correct = typedCorrect(q, mpTypedInput)
                                    mpFeedback = if (correct) "¡Correcto!" else "¡Error!"
                                    mpCorrectAnswer = q.accepted.firstOrNull()

                                    mpTypedInput = ""
                                    submitAnswer(
                                        db = db,
                                        code = rs.code,
                                        uid = uid,
                                        correct = correct,
                                        allQuestions = allQuestions,
                                        onFail = { mpError = it }
                                    )
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = SimpsonsYellow,
                                    unfocusedTextColor = SimpsonsYellow,
                                    focusedLabelColor = SimpsonsYellow,
                                    unfocusedLabelColor = SimpsonsYellow,
                                    focusedBorderColor = SimpsonsYellow,
                                    unfocusedBorderColor = SimpsonsYellow.copy(alpha = 0.7f),
                                    cursorColor = SimpsonsYellow
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    if (!myTurn) return@Button
                                    mpError = null

                                    val correct = typedCorrect(q, mpTypedInput)
                                    mpFeedback = if (correct) "¡Correcto!" else "¡Error!"
                                    mpCorrectAnswer = q.accepted.firstOrNull()

                                    mpTypedInput = ""
                                    submitAnswer(
                                        db = db,
                                        code = rs.code,
                                        uid = uid,
                                        correct = correct,
                                        allQuestions = allQuestions,
                                        onFail = { mpError = it }
                                    )
                                },
                                enabled = myTurn && mpTypedInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Responder", fontFamily = SimpsonsTitleFont)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            detachRoomListener()
                            roomState = null
                            roomCode = null
                            roomCodeInput = ""
                            mpError = null
                            mpFeedback = null
                            mpTypedInput = ""
                            mpCorrectAnswer = null
                            resetToMenu()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Leave", fontFamily = SimpsonsTitleFont)
                    }
                }
            }
        }
        Screen.END -> {
            AppScaffoldWithBackground(backgroundRes = R.drawable.pic38_dark) {
                val rs = roomState
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Game Over",
                        style = MaterialTheme.typography.titleLarge,
                        color = SimpsonsYellow,
                        fontFamily = SimpsonsTitleFont
                    )

                    if (rs != null) {
                        Text(
                            "Final: ${rs.p1Name} ${rs.scoresP1}  |  ${rs.p2Name ?: "Player 2"} ${rs.scoresP2}",
                            color = SimpsonsYellow,
                            fontFamily = SimpsonsTitleFont
                        )

                        val isTie = rs.scoresP1 == rs.scoresP2
                        val iAmP1 = uid == rs.p1Uid
                        val iAmP2 = uid == rs.p2Uid

                        val iAmWinner =
                            (!isTie && rs.scoresP1 > rs.scoresP2 && iAmP1) ||
                                    (!isTie && rs.scoresP2 > rs.scoresP1 && iAmP2)

                        val imageRes = when {
                            isTie -> undecisionImages.random()
                            iAmWinner -> winnerImages.random()
                            else -> loserImages.random()
                        }

                        val winnerName = when {
                            rs.scoresP1 > rs.scoresP2 -> rs.p1Name
                            rs.scoresP2 > rs.scoresP1 -> (rs.p2Name ?: "Player 2")
                            else -> "Tie"
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Winner: $winnerName!",
                            color = SimpsonsBlue,
                            fontFamily = SimpsonsTitleFont,

                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Preguntas jugadas: ${rs.maxQuestions}",
                            color = SimpsonsYellow,
                            fontFamily = SimpsonsTitleFont
                        )
                        Spacer(Modifier.height(16.dp))

                        Image(
                            painter = painterResource(id = imageRes),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // SINGLE PLAYER RESULT
                        Text(
                            "Jugador: $name",
                            color = SimpsonsYellow,
                            fontFamily = SimpsonsTitleFont
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Has acertado $singleScore de $singleTotalQuestions preguntas.",
                            color = SimpsonsYellow,
                            fontFamily = SimpsonsTitleFont
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { resetToMenu() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to menu", fontFamily = SimpsonsTitleFont)
                    }
                }
            }
        }
        Screen.MENU -> Unit // handled above
    }
}
