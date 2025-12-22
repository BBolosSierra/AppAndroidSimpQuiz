package com.example.appandroidsimpquiz

import android.content.Context
import android.os.Bundle
import android.util.Log
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import kotlin.random.Random

// ---------- ENUMS ----------
enum class Difficulty { HOMER, MARGE, LISA }
enum class Screen { MENU, SINGLE_GAME, MULTI_LOBBY, MULTI_GAME, END }

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
    val usedQuestionIds: Set<String> = emptySet()
)

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
    val pool = allQuestions.filter { it.difficulty == diff && it.id !in used }
    if (pool.isEmpty()) return null
    return pool[Random.nextInt(pool.size)].id
}

// ---------- FIRESTORE OPERATIONS ----------
private fun createRoom(
    db: FirebaseFirestore,
    uid: String,
    name: String,
    diff: Difficulty,
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
        "usedQuestionIds" to emptyList<String>()
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
        val used = (snap.get("usedQuestionIds") as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
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
        val used = (snap.get("usedQuestionIds") as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

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

// ---------- ACTIVITY ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)

        val allQuestions = loadQuestions(this)

        setContent {
            AppAndroidSimpQuizTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(allQuestions = allQuestions)
                }
            }
        }
    }
}

// ---------- ROOT ----------
@Composable
fun AppRoot(allQuestions: List<Question>) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    // Auth state that actually updates Compose
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
            Log.e("AUTH", "Firebase applicationId=" + FirebaseApp.getInstance().options.applicationId)

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
            Text("Signing in...")
            authError?.let { Text(it) }
        }
        return
    }
    val uid = uidState!! // safe non-null value from here onward

    // ---------- UI STATE ----------
    var screen by remember { mutableStateOf(Screen.MENU) }
    var difficulty by remember { mutableStateOf(Difficulty.HOMER) }
    var name by remember { mutableStateOf("") }

    // Single player
    var singleScore by remember { mutableStateOf(0) }
    var singleQuestion by remember { mutableStateOf<Question?>(null) }
    var singleTypedInput by remember { mutableStateOf("") }

    // Multiplayer
    var roomCodeInput by remember { mutableStateOf("") }
    var roomCode by remember { mutableStateOf<String?>(null) }
    var roomState by remember { mutableStateOf<RoomState?>(null) }
    var mpTypedInput by remember { mutableStateOf("") }
    var mpFeedback by remember { mutableStateOf<String?>(null) }
    var mpError by remember { mutableStateOf<String?>(null) }
    var roomListener by remember { mutableStateOf<ListenerRegistration?>(null) }

    fun startSingle() {
        singleScore = 0
        singleQuestion = allQuestions.filter { it.difficulty == difficulty }.randomOrNull()
        singleTypedInput = ""
        screen = Screen.SINGLE_GAME
    }

    fun nextSingle() {
        singleQuestion = allQuestions.filter { it.difficulty == difficulty }.randomOrNull()
        singleTypedInput = ""
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
                val diff = Difficulty.valueOf(((d["difficulty"] as? String) ?: "HOMER").uppercase())

                val hostUid = (d["hostUid"] as? String) ?: ""
                val p1Uid = (d["p1Uid"] as? String) ?: ""
                val p1Name = (d["p1Name"] as? String) ?: "Player 1"
                val p2Uid = d["p2Uid"] as? String
                val p2Name = d["p2Name"] as? String

                val s1 = ((d["scoresP1"] as? Long) ?: 0L).toInt()
                val s2 = ((d["scoresP2"] as? Long) ?: 0L).toInt()

                val turn = (d["turnUid"] as? String) ?: ""
                val qid = d["currentQuestionId"] as? String
                val used = (d["usedQuestionIds"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

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
                    usedQuestionIds = used
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

    // ---------- SCREENS ----------
    when (screen) {
        Screen.MENU -> {
            Column(Modifier.padding(16.dp)) {
                Text("Simpsons Quiz", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Text("Difficulty")
                Difficulty.values().forEach { d ->
                    Row {
                        RadioButton(selected = (d == difficulty), onClick = { difficulty = d })
                        Text(d.name)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { startSingle() },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Single Player") }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        mpError = null
                        mpFeedback = null
                        mpTypedInput = ""
                        roomCode = null
                        roomState = null
                        screen = Screen.MULTI_LOBBY
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Multiplayer (2 phones)") }
            }
        }

        Screen.SINGLE_GAME -> {
            val q = singleQuestion
            if (q == null) {
                Column(Modifier.padding(16.dp)) {
                    Text("No questions for ${difficulty.name}.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { screen = Screen.MENU }) { Text("Back") }
                }
                return
            }

            Column(Modifier.padding(16.dp)) {
                Text("Player: $name")
                Text("Score: $singleScore")
                Spacer(Modifier.height(12.dp))
                Text(q.prompt, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                when (q) {
                    is Question.Mcq -> {
                        q.options.forEachIndexed { idx, opt ->
                            Button(
                                onClick = {
                                    if (idx == q.correctIndex) singleScore++
                                    nextSingle()
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) { Text(opt) }
                        }
                    }
                    is Question.Typed -> {
                        OutlinedTextField(
                            value = singleTypedInput,
                            onValueChange = { singleTypedInput = it },
                            label = { Text("Your answer") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (typedCorrect(q, singleTypedInput)) singleScore++
                                nextSingle()
                            })
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            if (typedCorrect(q, singleTypedInput)) singleScore++
                            nextSingle()
                        }) { Text("Submit") }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(onClick = { screen = Screen.MENU }, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to menu")
                }
            }
        }

        Screen.MULTI_LOBBY -> {
            val rs = roomState
            Column(Modifier.padding(16.dp)) {
                Text("Multiplayer Lobby", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))

                mpError?.let {
                    Text(it)
                    Spacer(Modifier.height(8.dp))
                }

                if (roomCode == null) {
                    Button(
                        onClick = {
                            mpError = null
                            createRoom(
                                db = db,
                                uid = uid,
                                name = name,
                                diff = difficulty,
                                onDone = { code ->
                                    roomCode = code
                                    roomCodeInput = code
                                    attachRoomListener(code)
                                },
                                onFail = { mpError = it }
                            )
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create room (host)") }

                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = roomCodeInput,
                        onValueChange = { roomCodeInput = it.uppercase() },
                        label = { Text("Room code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                    ) { Text("Join room") }

                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { screen = Screen.MENU }, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to menu")
                    }
                    return
                }

                val code = roomCode!!
                Text("Room: $code")
                Spacer(Modifier.height(8.dp))

                if (rs == null) {
                    Text("Loading room...")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { screen = Screen.MENU }, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to menu")
                    }
                    return
                }

                Text("Difficulty: ${rs.difficulty.name}")
                Spacer(Modifier.height(8.dp))
                Text("Player 1: ${rs.p1Name}")
                Text("Player 2: ${rs.p2Name ?: "(waiting)"}")
                Spacer(Modifier.height(16.dp))

                val iAmHost = (uid == rs.hostUid)
                if (iAmHost) {
                    Button(
                        onClick = {
                            mpError = null
                            hostStartGame(
                                db = db,
                                allQuestions = allQuestions,
                                code = code,
                                hostUid = uid,
                                onFail = { mpError = it }
                            )
                        },
                        enabled = !rs.p2Uid.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Start game") }
                } else {
                    Text("Waiting for host to start.")
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
                        screen = Screen.MENU
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Leave") }
            }
        }

        Screen.MULTI_GAME -> {
            val rs = roomState
            if (rs == null) {
                Column(Modifier.padding(16.dp)) { Text("Loading game...") }
                return
            }

            val qid = rs.currentQuestionId
            val q = qid?.let { id -> allQuestions.firstOrNull { it.id == id } }

            Column(Modifier.padding(16.dp)) {
                Text("Room: ${rs.code}")
                Spacer(Modifier.height(8.dp))
                Text("Difficulty: ${rs.difficulty.name}")
                Spacer(Modifier.height(8.dp))

                Text("Score: ${rs.p1Name} ${rs.scoresP1}  |  ${rs.p2Name ?: "Player 2"} ${rs.scoresP2}")
                Spacer(Modifier.height(8.dp))

                val currentTurnName =
                    if (rs.turnUid == rs.p1Uid) rs.p1Name else (rs.p2Name ?: "Player 2")
                Text("Turn: $currentTurnName", style = MaterialTheme.typography.titleMedium)

                Spacer(Modifier.height(12.dp))
                mpFeedback?.let { Text(it); Spacer(Modifier.height(8.dp)) }
                mpError?.let { Text(it); Spacer(Modifier.height(8.dp)) }

                if (q == null) {
                    Text("Waiting for next question...")
                    return
                }

                Text(q.prompt, style = MaterialTheme.typography.titleMedium)
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
                                    mpFeedback = if (correct) "Correct" else "Wrong"
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
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) { Text(opt) }
                        }
                    }
                    is Question.Typed -> {
                        OutlinedTextField(
                            value = mpTypedInput,
                            onValueChange = { mpTypedInput = it },
                            label = { Text("Your answer") },
                            singleLine = true,
                            enabled = myTurn,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (!myTurn) return@KeyboardActions
                                mpError = null
                                val correct = typedCorrect(q, mpTypedInput)
                                mpFeedback = if (correct) "Correct" else "Wrong"
                                mpTypedInput = ""
                                submitAnswer(
                                    db = db,
                                    code = rs.code,
                                    uid = uid,
                                    correct = correct,
                                    allQuestions = allQuestions,
                                    onFail = { mpError = it }
                                )
                            })
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (!myTurn) return@Button
                                mpError = null
                                val correct = typedCorrect(q, mpTypedInput)
                                mpFeedback = if (correct) "Correct" else "Wrong"
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
                        ) { Text("Submit") }
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
                        screen = Screen.MENU
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Leave") }
            }
        }

        Screen.END -> {
            val rs = roomState
            Column(Modifier.padding(16.dp)) {
                Text("Game Over", style = MaterialTheme.typography.titleLarge)

                if (rs != null) {
                    Text("Final: ${rs.p1Name} ${rs.scoresP1}  |  ${rs.p2Name ?: "Player 2"} ${rs.scoresP2}")
                    val winner = when {
                        rs.scoresP1 > rs.scoresP2 -> rs.p1Name
                        rs.scoresP2 > rs.scoresP1 -> (rs.p2Name ?: "Player 2")
                        else -> "Tie"
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Winner: $winner")
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
                        screen = Screen.MENU
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back to menu") }
            }
        }
    }
}
