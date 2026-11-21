package com.localassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var brain: AssistantBrain
    private lateinit var content: LinearLayout
    private var currentPage = "home"
    private var currentInput: EditText? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var selectedTree: Uri? = null
    private var duplicateUris: List<Uri> = emptyList()
    private var captureTrainingPhrase = false
    private val io = Executors.newSingleThreadExecutor()
    private val palette: Palette
        get() = if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("purple", false)) Palette.PURPLE else Palette.TEAL

    private data class Palette(val primary: Int, val background: Int, val surface: Int, val text: Int) {
        companion object {
            val TEAL = Palette(0xFF087F8C.toInt(), 0xFFF5F7FA.toInt(), 0xFFFFFFFF.toInt(), 0xFF17212B.toInt())
            val PURPLE = Palette(0xFF6547A5.toInt(), 0xFFF7F5FA.toInt(), 0xFFFFFFFF.toInt(), 0xFF201A2B.toInt())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        brain = AssistantBrain(this)
        selectedTree = getSavedTree()
        render("home")
        handleIncomingIntent(intent)
        if (intent.getBooleanExtra("start_listening", false)) startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        if (intent.getBooleanExtra("start_listening", false)) startListening()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val shared = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        val bubbleText = intent.getStringExtra("bubble_text")
        val text = shared ?: bubbleText
        if (!text.isNullOrBlank()) {
            render("home")
            currentInput?.setText(text)
            if (!shared.isNullOrBlank()) showResult("الترجمة المحلية: ${brain.translateToArabic(text)}")
        }
    }

    private fun render(page: String) {
        currentPage = page
        currentInput = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        when (page) {
            "home" -> home()
            "settings" -> settings()
            "languages" -> languageSettings()
            "appearance" -> appearanceSettings()
            "services" -> serviceSettings()
            "training" -> training()
            "files" -> files()
        }
    }

    private fun home() {
        title("المساعد الذكي المحلي")
        body("يفهم أوامرك الأساسية ويعمل دون اتصال بالإنترنت. يمكنك إضافة تصحيحات محلية من صفحة التدريب.")
        val input = edit("اكتب أمراً أو اسأل المساعد")
        currentInput = input
        action("نفّذ الأمر", palette.primary) { processCommand(input.text.toString()) }
        action("تحدث إلى المساعد", 0xFF2878C7.toInt()) { requestMicrophone() }
        action("تدريب وتصحيح الكلمات", 0xFFB78103.toInt()) { render("training") }
        action("الملفات والتكرارات", 0xFF2878C7.toInt()) { render("files") }
        action("الأيقونة العائمة", 0xFF087F8C.toInt()) { requestBubble() }
        action("الإعدادات", palette.text) { render("settings") }
        action("ترجمة النص إلى العربية", 0xFF2878C7.toInt()) {
            showResult(brain.translateToArabic(input.text.toString()))
        }
    }

    private fun settings() {
        title("الإعدادات")
        body("التحكم محلي على الجهاز. كل صلاحية اختيارية ويمكن إيقافها من إعدادات Android.")
        action("اللغات والفهم", palette.primary) { render("languages") }
        action("الألوان والواجهة", 0xFF2878C7.toInt()) { render("appearance") }
        action("الخدمات والصلاحيات", palette.text) { render("services") }
        action("إدارة الملفات", 0xFF2878C7.toInt()) { render("files") }
        backButton()
    }

    private fun languageSettings() {
        title("اللغات والفهم")
        body("واجهة المساعد بالعربية. يتعرف المحرك المحلي على أوامر أساسية بالعربية والدارجة المغربية والإنجليزية والفرنسية، ويتعلم التصحيحات التي تحفظها.")
        body("الترجمة المضمنة قاموس محدود يعمل دون اتصال؛ قد تبقى الكلمات غير الموجودة في القاموس كما هي.")
        backButton()
    }

    private fun appearanceSettings() {
        title("الألوان والواجهة")
        body("الأخضر/التركوازي للتفعيل والنجاح، الأصفر للمراجعة، والأحمر للعمليات الخطرة.")
        action("استخدام اللون التركوازي", 0xFF087F8C.toInt()) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("purple", false).apply()
            render("appearance")
        }
        action("استخدام اللون البنفسجي", 0xFF6547A5.toInt()) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("purple", true).apply()
            render("appearance")
        }
        backButton()
    }

    private fun serviceSettings() {
        title("الخدمات والصلاحيات")
        body("الوصول العائم يحتاج إذن الظهور فوق التطبيقات. الميكروفون يطلب الإذن عند بدء التسجيل، ويستخدم التعرف الصوتي المحلي عند توفره في Android.")
        body("لا يراقب التطبيق محتوى التطبيقات الأخرى أو الحقول الحساسة. استخدم مشاركة النص أو خيار «معالجة النص» من قائمة تحديد النص.")
        action("إذن الأيقونة العائمة", palette.primary) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        action("إعدادات تسهيلات الاستخدام", palette.text) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        action("إعدادات Wi-Fi", 0xFF2878C7.toInt()) { openSettingsPage(Settings.ACTION_WIFI_SETTINGS) }
        action("إعدادات Bluetooth", 0xFF2878C7.toInt()) { openSettingsPage(Settings.ACTION_BLUETOOTH_SETTINGS) }
        action("إعدادات الشاشة", 0xFF2878C7.toInt()) { openSettingsPage(Settings.ACTION_DISPLAY_SETTINGS) }
        action("إعدادات الصوت", 0xFF2878C7.toInt()) { openSettingsPage(Settings.ACTION_SOUND_SETTINGS) }
        action("إعدادات الموقع", 0xFF2878C7.toInt()) { openSettingsPage(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }
        action("إعدادات التطبيق", palette.text) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        backButton()
    }

    private fun training() {
        title("التدريب المحلي")
        body("أدخل العبارة التي تريد تصحيحها وما يفترض أن يتعرف عليه المساعد. تحفظ البيانات على هذا الجهاز فقط.")
        val original = edit("العبارة الأصلية أو غير الصحيحة")
        val corrected = edit("العبارة الصحيحة أو الأمر المقصود")
        action("حفظ التصحيح", 0xFF26834A.toInt()) {
            if (original.text.isBlank() || corrected.text.isBlank()) {
                message("أدخل العبارتين قبل الحفظ.")
            } else {
                brain.learn(original.text.toString(), corrected.text.toString())
                original.text.clear()
                corrected.text.clear()
                message("تم حفظ التصحيح محلياً.")
            }
        }
        action("تسجيل العبارة الأصلية بالصوت", 0xFF2878C7.toInt()) {
            currentInput = original
            requestMicrophone(trainingPhrase = true)
        }
        backButton()
    }

    private fun files() {
        title("إدارة الملفات")
        body("اختر مجلداً بنفسك عبر منتقي Android. لا يملك التطبيق وصولاً شاملاً للملفات. يمكنه فحص الملفات المتكررة داخل المجلد الذي تختاره فقط.")
        action("اختيار مجلد", palette.primary) { chooseFolder() }
        action("فحص التكرارات", 0xFFB78103.toInt()) { scanDuplicates() }
        backButton()
    }

    private fun title(value: String) {
        val view = TextView(this).apply {
            text = value
            textSize = 25f
            setTextColor(palette.text)
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(10))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        content.addView(view)
    }

    private fun body(value: String) {
        val view = TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(palette.text)
            gravity = Gravity.RIGHT
            setPadding(0, dp(6), 0, dp(12))
        }
        content.addView(view)
    }

    private fun edit(hint: String): EditText {
        val field = EditText(this).apply {
            this.hint = hint
            textSize = 16f
            setTextColor(palette.text)
            setHintTextColor(0xFF7B8794.toInt())
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(palette.surface)
        }
        content.addView(field, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        return field
    }

    private fun action(label: String, color: Int, onClick: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
            minimumHeight = dp(50)
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        content.addView(button, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
    }

    private fun backButton() {
        action("العودة للرئيسية", palette.text) { render("home") }
    }

    private fun showResult(text: String) {
        body(text.ifBlank { "اكتب نصاً أولاً." })
    }

    private fun processCommand(raw: String) {
        if (raw.isBlank()) {
            message("اكتب أمراً أولاً.")
            return
        }
        val corrected = brain.correctionFor(raw) ?: raw
        val command = AssistantBrain.normalize(corrected)
        when {
            includesAny(command, "ملف", "الملفات", "files", "file manager", "fichiers") -> render("files")
            includesAny(command, "اعدادات", "الاعدادات", "settings", "parametres", "الإعدادات") -> openSystemSettings()
            includesAny(command, "فقاعة", "عائمة", "bubble", "flottante") -> requestBubble()
            openPrefixes.any(command::startsWith) -> {
                val prefix = openPrefixes.first(command::startsWith)
                openApp(command.removePrefix(prefix).trim())
            }
            command.startsWith("ترجم ") || command.startsWith("translate ") ->
                showResult(brain.translateToArabic(raw.substringAfter(' ')))
            else -> {
                val launched = tryOpenApp(command)
                if (!launched) showResult("لم أتعرف على الأمر. يمكنك حفظ تصحيح له من صفحة التدريب.")
            }
        }
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            message("تعذر فتح إعدادات النظام.")
        }
    }

    private fun openSettingsPage(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            message("صفحة الإعداد المطلوبة غير متاحة على هذا الجهاز.")
        }
    }

    private fun openApp(name: String) {
        if (name.isBlank() || !tryOpenApp(name)) message("لم يتم العثور على تطبيق بهذا الاسم.")
    }

    private fun tryOpenApp(name: String): Boolean {
        val normalizedName = AssistantBrain.normalize(name)
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(launchIntent, 0)
        val target = candidates.firstOrNull {
            AssistantBrain.normalize(it.loadLabel(packageManager).toString()) == normalizedName
        } ?: candidates.firstOrNull {
            val label = AssistantBrain.normalize(it.loadLabel(packageManager).toString())
            label.contains(normalizedName) || normalizedName.contains(label)
        } ?: return false
        val intent = packageManager.getLaunchIntentForPackage(target.activityInfo.packageName) ?: return false
        startActivity(intent)
        return true
    }

    private fun includesAny(text: String, vararg candidates: String) =
        candidates.any { text.contains(AssistantBrain.normalize(it)) }

    private fun requestBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            message("اسمح بالظهور فوق التطبيقات ثم اضغط زر الأيقونة العائمة مجدداً.")
            return
        }
        val serviceIntent = Intent(this, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        message("تم تشغيل الأيقونة. اضغطها لإظهار الأدوات.")
    }

    private fun requestMicrophone(trainingPhrase: Boolean = false) {
        captureTrainingPhrase = trainingPhrase
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        } else startListening()
    }

    private fun startListening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            captureTrainingPhrase = false
            message("التعرف الصوتي المحلي غير متاح على هذا الجهاز. استخدم الكتابة لضمان العمل دون إنترنت.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophone()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            message("خدمة التعرف الصوتي غير متاحة.")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
            setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onError(error: Int) {
                    captureTrainingPhrase = false
                    message("لم يكتمل التعرف على الصوت. جرّب الكتابة أو أعد المحاولة.")
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        currentInput?.setText(spoken)
                        if (captureTrainingPhrase) {
                            captureTrainingPhrase = false
                            message("تم إدخال العبارة. اكتب التصحيح ثم احفظه.")
                        } else {
                            processCommand(spoken)
                        }
                    }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-MA")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            startListening(intent)
        }
    }

    private fun chooseFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER)
        } catch (_: ActivityNotFoundException) {
            message("منتقي المجلدات غير متاح.")
        }
    }

    @Deprecated("Handled for the system document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                selectedTree = uri
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).apply()
                message("تم اختيار المجلد.")
            } catch (e: SecurityException) {
                message("تعذر حفظ إذن المجلد: ${e.localizedMessage ?: "تم رفض الإذن"}")
            }
        }
    }

    private fun getSavedTree(): Uri? = getSharedPreferences("settings", MODE_PRIVATE)
        .getString("tree_uri", null)?.let(Uri::parse)

    private fun scanDuplicates() {
        val tree = selectedTree
        if (tree == null) {
            message("اختر مجلداً أولاً.")
            return
        }
        message("جارٍ فحص الملفات داخل المجلد المختار...")
        io.execute {
            try {
                val files = mutableListOf<Pair<Uri, String>>()
                collectFiles(tree, DocumentsContract.getTreeDocumentId(tree), files, 0)
                val seen = mutableMapOf<String, Uri>()
                val duplicates = mutableListOf<Uri>()
                files.forEach { (uri, _) ->
                    val digest = contentResolver.openInputStream(uri)?.use { stream ->
                        val sha = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            sha.update(buffer, 0, count)
                        }
                        sha.digest().joinToString("") { byte -> "%02x".format(byte) }
                    }
                    if (digest != null && seen.putIfAbsent(digest, uri) != null) duplicates.add(uri)
                }
                runOnUiThread {
                    duplicateUris = duplicates
                    if (duplicates.isEmpty()) {
                        message("لم يتم العثور على ملفات متطابقة في المجلد.")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("ملفات مكررة")
                            .setMessage("عُثر على ${duplicates.size} نسخة مكررة. تُحذف النسخ الإضافية فقط بعد موافقتك.")
                            .setNegativeButton("إلغاء", null)
                            .setPositiveButton("حذف النسخ", null)
                            .create()
                            .also { dialog ->
                                dialog.setOnShowListener {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFFB3261E.toInt())
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                        dialog.dismiss()
                                        confirmDuplicateDeletion()
                                    }
                                }
                                dialog.show()
                            }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { message("فشل فحص المجلد: ${e.localizedMessage ?: "تعذر قراءة بعض الملفات"}") }
            }
        }
    }

    private fun collectFiles(tree: Uri, parentId: String, output: MutableList<Pair<Uri, String>>, depth: Int) {
        if (depth > 20 || output.size >= 1000) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext() && output.size < 1000) {
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn) ?: "ملف"
                if (cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    collectFiles(tree, id, output, depth + 1)
                } else {
                    output.add(DocumentsContract.buildDocumentUriUsingTree(tree, id) to name)
                }
            }
        }
    }

    private fun confirmDuplicateDeletion() {
        val targets = duplicateUris.toList()
        io.execute {
            var deleted = 0
            var failures = 0
            targets.forEach { uri ->
                try {
                    if (DocumentsContract.deleteDocument(contentResolver, uri)) deleted++ else failures++
                } catch (_: Exception) {
                    failures++
                }
            }
            runOnUiThread { message("حُذف $deleted ملف مكرر${if (failures > 0) "، وتعذر حذف $failures" else ""}.") }
        }
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
            else {
                captureTrainingPhrase = false
                message("لم يُمنح إذن الميكروفون.")
            }
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO = 10
        private const val REQUEST_FOLDER = 11
        private val openPrefixes = listOf("افتح ", "فتح ", "حل ", "بغيت نفتح ", "سير ل ", "open ", "ouvrir ", "bghit n7ell ")
    }
}
