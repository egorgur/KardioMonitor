"""Авторизация Huami 2021 (Mi Band 6 с прошивкой 1.0.6.10+).

Порт из Gadgetbridge: InitOperation2021, Huami2021ChunkedEncoder/Decoder, ECDH_B163.
Схема:
  1. обмен публичными ключами ECDH на кривой NIST B-163 (эндпоинт 0x0082);
  2. браслет присылает случайное число и свой публичный ключ;
  3. сессионный ключ = shared_x[8:24] XOR auth_key;
  4. отправляем случайное число, зашифрованное auth_key и сессионным ключом.
Сообщения передаются кусками через характеристики 0x0016 (запись) и 0x0017 (чтение).
"""

import secrets

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# --- NIST B-163: y^2 + xy = x^3 + x^2 + b над GF(2^163) ---

DEGREE = 163
POLY = (1 << 163) | 0xC9  # x^163 + x^7 + x^6 + x^3 + 1
B = 0x20A601907B8C953CA1481EB10512F78744A3205FD
GX = 0x3F0EBA16286A2D57EA0991168D4994637E8343E36
GY = 0x0D51FBC6C71A0094FA2CDD545B11C5C0C797324F1
ORDER = 0x40000000000000000000292FE77E70C12A4234C33
KEY_BYTES = 24  # 6 x uint32, little-endian


def gf_mul(a: int, b: int) -> int:
    result = 0
    while b:
        if b & 1:
            result ^= a
        b >>= 1
        a <<= 1
        if a >> DEGREE:
            a ^= POLY
    return result


def gf_inv(a: int) -> int:
    # расширенный алгоритм Евклида для многочленов над GF(2)
    u, v, g1, g2 = a, POLY, 1, 0
    while u != 1:
        j = u.bit_length() - v.bit_length()
        if j < 0:
            u, v, g1, g2 = v, u, g2, g1
            j = -j
        u ^= v << j
        g1 ^= g2 << j
    return g1


def on_curve(x: int, y: int) -> bool:
    x2 = gf_mul(x, x)
    return gf_mul(y, y) ^ gf_mul(x, y) == gf_mul(x2, x) ^ x2 ^ B


def point_double(x: int, y: int) -> tuple[int, int]:
    if x == 0:
        return 0, 0
    lam = gf_mul(gf_inv(x), y) ^ x
    x3 = gf_mul(lam, lam) ^ lam ^ 1
    y3 = gf_mul(x, x) ^ gf_mul(lam ^ 1, x3)
    return x3, y3


def point_add(x1: int, y1: int, x2: int, y2: int) -> tuple[int, int]:
    if x2 == 0 and y2 == 0:
        return x1, y1
    if x1 == 0 and y1 == 0:
        return x2, y2
    if x1 == x2:
        return point_double(x1, y1) if y1 == y2 else (0, 0)
    lam = gf_mul(gf_inv(x1 ^ x2), y1 ^ y2)
    x3 = gf_mul(lam, lam) ^ lam ^ x1 ^ x2 ^ 1
    y3 = gf_mul(lam, x1 ^ x3) ^ x3 ^ y1
    return x3, y3


def point_mul(x: int, y: int, k: int) -> tuple[int, int]:
    rx, ry = 0, 0
    for i in reversed(range(k.bit_length())):
        rx, ry = point_double(rx, ry)
        if (k >> i) & 1:
            rx, ry = point_add(rx, ry, x, y)
    return rx, ry


def _scalar(private_key: bytes) -> int:
    # как в tiny-ECDH: обнулить биты >= 162, чтобы скаляр был меньше порядка группы
    return int.from_bytes(private_key, "little") & ((1 << (ORDER.bit_length() - 1)) - 1)


def _encode_point(x: int, y: int) -> bytes:
    return x.to_bytes(KEY_BYTES, "little") + y.to_bytes(KEY_BYTES, "little")


def ecdh_generate_keys() -> tuple[bytes, bytes]:
    while True:
        private_key = secrets.token_bytes(KEY_BYTES)
        if int.from_bytes(private_key, "little").bit_length() >= DEGREE // 2:
            return private_key, _encode_point(*point_mul(GX, GY, _scalar(private_key)))


def ecdh_shared_secret(private_key: bytes, remote_public: bytes) -> bytes:
    x = int.from_bytes(remote_public[:KEY_BYTES], "little")
    y = int.from_bytes(remote_public[KEY_BYTES:2 * KEY_BYTES], "little")
    if (x == 0 and y == 0) or not on_curve(x, y):
        raise ValueError("публичный ключ браслета не лежит на кривой B-163")
    return _encode_point(*point_mul(x, y, _scalar(private_key)))


def aes_ecb(data: bytes, key: bytes) -> bytes:
    encryptor = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
    return encryptor.update(data) + encryptor.finalize()


# --- Передача сообщений кусками (только незашифрованные — для авторизации хватает) ---

ENDPOINT_AUTH = 0x0082
MIN_MTU = 23


class ChunkedEncoder:
    def __init__(self, mtu: int = MIN_MTU):
        self.max_chunk = min(512, max(MIN_MTU, mtu) - 3)
        self.handle = 0

    def encode(self, endpoint: int, data: bytes) -> list[bytes]:
        self.handle = (self.handle + 1) & 0xFF
        chunks, offset, count = [], 0, 0
        while offset < len(data) or count == 0:
            first = count == 0
            header_size = 11 if first else 5
            part = data[offset:offset + self.max_chunk - header_size]
            offset += len(part)
            flags = 0x01 if first else 0x00
            if offset >= len(data):
                flags |= 0x02 | 0x04  # последний кусок, нужен ack
            header = bytes([0x03, flags, 0x00, self.handle, count])
            if first:
                header += len(data).to_bytes(4, "little") + endpoint.to_bytes(2, "little")
            chunks.append(header + part)
            count += 1
        return chunks


class ChunkedDecoder:
    """Собирает сообщение из кусков.

    feed() по готовности возвращает (endpoint, payload, encrypted, ack). Зашифрованные
    сообщения не расшифровываются (payload сырой), но подтверждение для них формируется —
    иначе браслет будет повторять отправку.
    """

    def __init__(self):
        self._buffer = bytearray()
        self._length = 0
        self._endpoint = 0

    def feed(self, data: bytes) -> tuple[int, bytes, bool, bytes | None] | None:
        if len(data) < 5 or data[0] != 0x03:
            return None
        flags, handle, count = data[1], data[3], data[4]
        encrypted = bool(flags & 0x08)
        body = data[5:]
        if flags & 0x01:
            self._length = int.from_bytes(body[0:4], "little")
            self._endpoint = int.from_bytes(body[4:6], "little")
            self._buffer = bytearray()
            body = body[6:]
        self._buffer += body
        if not flags & 0x02:
            return None
        ack = bytes([0x04, 0x00, handle, 0x01, count]) if flags & 0x04 else None
        payload = bytes(self._buffer) if encrypted else bytes(self._buffer[:self._length])
        return self._endpoint, payload, encrypted, ack


class Auth2021:
    """Состояние авторизации: формирует команды и разбирает ответы браслета."""

    def __init__(self, auth_key: bytes):
        self.auth_key = auth_key
        self.private_key, self.public_key = ecdh_generate_keys()
        self.session_key: bytes | None = None
        self.sequence_number: int | None = None

    def pubkey_command(self) -> bytes:
        return bytes([0x04, 0x02, 0x00, 0x02]) + self.public_key

    def answer_challenge(self, payload: bytes) -> bytes:
        """Ответ на 10 04 01 <random 16> <pubkey 48>."""
        remote_random = payload[3:19]
        shared = ecdh_shared_secret(self.private_key, payload[19:67])
        self.sequence_number = int.from_bytes(shared[0:4], "little")
        self.session_key = bytes(s ^ k for s, k in zip(shared[8:24], self.auth_key))
        return (b"\x05" + aes_ecb(remote_random, self.auth_key)
                + aes_ecb(remote_random, self.session_key))
