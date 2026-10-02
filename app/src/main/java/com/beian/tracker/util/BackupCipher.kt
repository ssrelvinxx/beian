package com.beian.tracker.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份包的对称加密（AES-256-GCM）。
 *
 * 为什么要有它：
 *   导出的 .hh 包会经微信 / QQ 转发，链路里任何一环（聊天记录、云端备份、
 *   误发给别人）都能拿到文件。写明文 JSON 的话，用文本编辑器打开就是完整
 *   的定位轨迹和 App 使用记录。
 *
 * ── 两种密钥来源 ─────────────────────────────────────────────────────────────
 *
 * 1. **用户自设密码**（[encryptWithPassword] / [decryptWithPassword]）
 *    在设置页填一个双方约定的密码，导出时用它加密、导入时输同一个密码解密。
 *    这是真正的保护：密码只在两台手机上，不随 APK 分发，反编译也拿不到。
 *    ⚠️ 密码忘了**无法找回** —— 我们不存、也无法推算，已导出的包就永久打不开。
 *
 * 2. **内置默认口令**（[encrypt] / [decrypt]）
 *    没设密码时的兜底，保证「不设密码也能一键导出导入」这条路不断。
 *    代价如实说明：口令写在 APK 里，反编译能拿到，属于
 *    「防随手打开看一眼」，**不是**密码学意义上的保护。
 *
 * 文件格式（纯文本，便于任何方式传输）：
 *
 *     hh-enc-v2
 *     <Base64(salt ‖ iv ‖ 密文 + GCM tag)>
 *
 *   salt 每次导出随机生成，和密文一起走 —— 同一个密码两次导出
 *   产出的密文也完全不同（否则相同明文 = 相同密文，
 *   转发链条上能直接看出「这两份是同一份数据」）。
 *
 * 兼容性：
 *   - v1（内置固定盐）的包仍能解 —— 见 [LEGACY_MARKER]。
 *   - 不带任何标记的是更早的明文 JSON 包，原样返回，交给 BackupCodec 解析。
 */
object BackupCipher {

    /** 当前格式标记（带随机盐）。 */
    const val MARKER = "hh-enc-v2"

    /**
     * 上一版格式标记（固定盐，无随机盐）。
     *
     * 只在已经发出去过旧包时才需要保留。当前没有任何正式包用过 v1，
     * 但保留解析成本极低（一段 if），而漏掉的后果是对方的包直接导不进来，
     * 所以留着。
     */
    private const val LEGACY_MARKER = "hh-enc-v1"

    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val SALT_BYTES = 16
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /**
     * 内置兜底口令与盐。仅用于「用户没设密码」的场景。
     *
     * ⚠️ 见类注释：这不是真正的密钥管理。写在这里是因为
     *    「没设密码也能一键导入」的需求必须有个密钥可用。
     */
    private const val FALLBACK_PASSPHRASE = "huahua-tracker-backup-v1"
    private const val FALLBACK_SALT = "hh-backup-salt-2026"

    /**
     * PBKDF2 迭代次数。
     *
     * 取 12000 是折中：更高更抗暴力破解，但导入大包时每次都要重新派生，
     * 迭代太高会让「点开文件」多等一截。
     *
     * ⚠️ 用户自设密码时这个值偏低（现代建议 10 万+）。之所以不上调：
     *    包是给自己人用的，攻击面不是「拿到密文跑字典」，
     *    而是「文件在微信里被别人看到」—— 那种场景下迭代次数没有意义。
     *    真正的强度来自密码本身的熵（所以提供了随机生成按钮）。
     */
    private const val ITERATIONS = 12_000

    // ── 密钥派生 ──────────────────────────────────────────────────────────────

    /**
     * 从密码 + 盐派生 AES 密钥。
     *
     * ⚠️ 每次调用都会真的跑一遍 PBKDF2（约几十毫秒），**不要在循环里调**。
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            ITERATIONS,
            KEY_BITS,
        )
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    // ── 加密 ──────────────────────────────────────────────────────────────────

    /**
     * 用**用户自设密码**加密。
     *
     * ⚠️ 必须在 IO 线程调用：PBKDF2 派生 + AES 加密都是 CPU 密集操作，
     *    包大时留在主线程会卡界面。
     *
     * @param password 空串时退回内置口令（见 [encrypt]），保证不会因为没有
     *                 密码而导不出东西
     * @return 形如 "hh-enc-v2\n<Base64>" 的文本
     */
    fun encryptWithPassword(plain: String, password: String): String {
        if (password.isEmpty()) return encrypt(plain)

        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        return seal(MARKER, plain, key, salt)
    }

    /**
     * 用**内置兜底口令**加密（用户没设密码时的路径）。
     *
     * 仍然是加密的，只是密钥随 APK 分发 —— 见类注释对保护强度的说明。
     */
    fun encrypt(plain: String): String {
        val salt = FALLBACK_SALT.toByteArray(Charsets.UTF_8)
        val key = deriveKey(FALLBACK_PASSPHRASE, salt)
        return seal(MARKER, plain, key, salt)
    }

    /** 共用封装：marker + Base64(salt ‖ iv ‖ 密文)。 */
    private fun seal(
        marker: String,
        plain: String,
        key: SecretKeySpec,
        salt: ByteArray,
    ): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        // salt 和 iv 都不必保密，但解密时必须拿到同样的值，所以一起打包。
        val packed = salt + iv + body
        return marker + "\n" + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    // ── 解密 ──────────────────────────────────────────────────────────────────

    /**
     * 用**用户自设密码**解密。
     *
     * @param password 用户输入的密码。**允许为空**，此时退回内置口令 ——
     *        因为「对方没设密码」导出的包本身就是用内置口令加密的，
     *        导入方无从得知这一点，只能拿空密码来试。
     *        详见下方注释。
     * @throws IllegalArgumentException 密码不对 / 文件损坏 / 不是本 App 的包，
     *         带中文原因，调用方直接显示即可
     */
    fun decryptWithPassword(text: String, password: String): String {
        val head = normalize(text)
        if (head.startsWith(LEGACY_MARKER)) {
            // v1 用的是内置固定盐，用户密码在这个格式下没有意义 ——
            // 它本来就只可能由内置口令加密而来。
            return legacyOpen(
                head,
                deriveKey(FALLBACK_PASSPHRASE, FALLBACK_SALT.toByteArray(Charsets.UTF_8)),
            )
        }
        if (!head.startsWith(MARKER)) {
            // 明文旧包：没有密码可用，也无从校验，直接交给上层解析
            return text
        }

        // ⚠️ 空密码不能直接报错。
        //
        // 导出方「没设密码」时，包是用内置口令加密的 —— 但加密包本身
        // **不携带「用了哪个口令」的信息**（带上去等于告诉别人用内置口令
        // 试一下就能开）。导入方拿到这种包，密码框是空的，
        // 如果这里直接抛「需要密码」，对方就会卡死在一个他根本不知道
        // 该填什么的输入框前 —— 那是我们这边的设计把他堵住了，
        // 不是他操作错。
        //
        // 所以空密码先试内置口令。试不开也没关系：会走下面的 catch，
        // 报「密码不正确」，用户可以再手填真实密码重试。
        val effective = password.ifEmpty { FALLBACK_PASSPHRASE }
        return open(head, deriveKey(effective, extractSalt(head)))
    }

    /**
     * 用**内置兜底口令**解密（用户没设密码时）。
     *
     * ⚠️ 这条路只能开「用内置口令加密的包」。
     *    对方用了自设密码的包，这里必然失败并报错 —— 那是正确行为，
     *    界面会据此提示用户去输密码。
     */
    fun decrypt(text: String): String {
        val head = normalize(text)
        return when {
            head.startsWith(MARKER) ->
                open(head, deriveKey(FALLBACK_PASSPHRASE, extractSalt(head)))
            head.startsWith(LEGACY_MARKER) ->
                legacyOpen(head, deriveKey(FALLBACK_PASSPHRASE, FALLBACK_SALT.toByteArray(Charsets.UTF_8)))
            // 明文旧包
            else -> text
        }
    }

    /**
     * 判断一段文本是不是加密包（不看密码对不对）。
     *
     * 界面用它来决定要不要弹「输密码」框：明文旧包不该弹。
     */
    fun isEncrypted(text: String): Boolean {
        val head = normalize(text)
        return head.startsWith(MARKER) || head.startsWith(LEGACY_MARKER)
    }

    /**
     * 去掉 UTF-8 BOM 和前置空白。
     *
     * 某些文件管理器 / 聊天软件转发纯文本文件时会加上 BOM 或空行，
     * 直接比对首行会误判成「明文包」，然后当成 JSON 解析失败，
     * 报一个莫名其妙的错。
     */
    private fun normalize(text: String): String =
        text.trimStart('\uFEFF', '\n', '\r', ' ', '\t')

    /** 取出包里的盐。v2 的盐在 Base64 负载的最前面 16 字节。 */
    private fun extractSalt(head: String): ByteArray {
        val payload = payloadOf(head)
        if (payload.size <= SALT_BYTES) throw IllegalArgumentException("数据包已损坏")
        return payload.copyOfRange(0, SALT_BYTES)
    }

    /** 用给定密钥解开 v2 格式的包（负载 = salt ‖ iv ‖ 密文）。 */
    private fun open(head: String, key: SecretKeySpec): String {
        val payload = payloadOf(head)
        if (payload.size <= SALT_BYTES + IV_BYTES) {
            throw IllegalArgumentException("数据包已损坏")
        }
        val ivStart = SALT_BYTES
        val iv = payload.copyOfRange(ivStart, ivStart + IV_BYTES)
        val body = payload.copyOfRange(ivStart + IV_BYTES, payload.size)
        return unwrap(iv, body, key)
    }

    /**
     * 解开 v1 格式的包（负载 = iv ‖ 密文，**没有盐**）。
     *
     * 盐是固定的 [FALLBACK_SALT]，不随包走，所以这里的偏移与 [open] 不同。
     */
    private fun legacyOpen(head: String, key: SecretKeySpec): String {
        val payload = payloadOf(head)
        if (payload.size <= IV_BYTES) throw IllegalArgumentException("数据包已损坏")
        val iv = payload.copyOfRange(0, IV_BYTES)
        val body = payload.copyOfRange(IV_BYTES, payload.size)
        return unwrap(iv, body, key)
    }

    private fun unwrap(iv: ByteArray, body: ByteArray, key: SecretKeySpec): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val plain = try {
            // GCM 自带完整性校验：密文被改过一个字节，这里就会抛
            // AEADBadTagException。所以「密码错」和「文件被改」在这里
            // 是同一个异常，只能用同一句话提示。
            cipher.doFinal(body)
        } catch (_: Exception) {
            throw IllegalArgumentException("密码不正确，或文件已损坏")
        }
        return String(plain, Charsets.UTF_8)
    }

    /** 解出标记行之后的 Base64 负载。 */
    private fun payloadOf(head: String): ByteArray {
        val lineEnd = head.indexOf('\n')
        if (lineEnd < 0) throw IllegalArgumentException("数据包已损坏")
        // 去掉换行和空格：Base64 内容可能被传输工具折行
        val b64 = head.substring(lineEnd + 1).filterNot { it.isWhitespace() }
        return try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            throw IllegalArgumentException("数据包已损坏")
        }
    }

    // ── 随机密码 ──────────────────────────────────────────────────────────────

    /** 随机密码用的字符集：去掉了 0/O、1/l/I 这些看起来一样的字符。 */
    private const val PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /**
     * 生成一个随机密码，供「随机生成」按钮使用。
     *
     * 默认 16 位：约 95 bit 熵，暴力破解不现实，长度也还在能手动抄写的范围内。
     *
     * ⚠️ 用 [SecureRandom] 而不是 Random —— 后者是可预测的伪随机，
     *    用来生成密码等于没加密。
     */
    fun generatePassword(length: Int = 16): String {
        val rnd = SecureRandom()
        return buildString(length) {
            repeat(length) {
                append(PASSWORD_ALPHABET[rnd.nextInt(PASSWORD_ALPHABET.length)])
            }
        }
    }
}
