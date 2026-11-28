#include "BackupArchive.h"

#include <cstdint>
#include <cstring>
#include <fstream>

namespace xime {
namespace backup {

namespace {

uint32_t g_crcTable[256];
bool g_crcInit = false;

void InitCrc() {
    if (g_crcInit) {
        return;
    }
    for (uint32_t i = 0; i < 256; ++i) {
        uint32_t c = i;
        for (int k = 0; k < 8; ++k) {
            c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
        }
        g_crcTable[i] = c;
    }
    g_crcInit = true;
}

uint32_t Crc32(const std::string& data) {
    InitCrc();
    uint32_t crc = 0xFFFFFFFF;
    for (unsigned char b : data) {
        crc = g_crcTable[(crc ^ b) & 0xFF] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFF;
}

void PutU16(std::string& o, uint16_t v) {
    o.push_back(static_cast<char>(v & 0xFF));
    o.push_back(static_cast<char>((v >> 8) & 0xFF));
}
void PutU32(std::string& o, uint32_t v) {
    o.push_back(static_cast<char>(v & 0xFF));
    o.push_back(static_cast<char>((v >> 8) & 0xFF));
    o.push_back(static_cast<char>((v >> 16) & 0xFF));
    o.push_back(static_cast<char>((v >> 24) & 0xFF));
}

uint16_t GetU16(const std::string& s, size_t p) {
    return static_cast<uint16_t>((unsigned char)s[p] |
                                 ((unsigned char)s[p + 1] << 8));
}
uint32_t GetU32(const std::string& s, size_t p) {
    return static_cast<uint32_t>((unsigned char)s[p] |
                                 ((unsigned char)s[p + 1] << 8) |
                                 ((unsigned char)s[p + 2] << 16) |
                                 ((unsigned char)s[p + 3] << 24));
}

} // namespace

bool WriteZip(const std::string& path, const std::vector<ZipEntry>& entries) {
    std::string local;
    std::string central;
    for (const auto& e : entries) {
        const uint32_t crc = Crc32(e.content);
        const uint32_t size = static_cast<uint32_t>(e.content.size());
        const uint32_t offset = static_cast<uint32_t>(local.size());

        PutU32(local, 0x04034b50);
        PutU16(local, 20);
        PutU16(local, 0); // flags
        PutU16(local, 0); // method=store
        PutU16(local, 0); // time
        PutU16(local, 0); // date
        PutU32(local, crc);
        PutU32(local, size);
        PutU32(local, size);
        PutU16(local, static_cast<uint16_t>(e.name.size()));
        PutU16(local, 0); // extra
        local += e.name;
        local += e.content;

        PutU32(central, 0x02014b50);
        PutU16(central, 20); // version made by
        PutU16(central, 20); // version needed
        PutU16(central, 0);
        PutU16(central, 0);
        PutU16(central, 0);
        PutU16(central, 0);
        PutU32(central, crc);
        PutU32(central, size);
        PutU32(central, size);
        PutU16(central, static_cast<uint16_t>(e.name.size()));
        PutU16(central, 0); // extra
        PutU16(central, 0); // comment
        PutU16(central, 0); // disk
        PutU16(central, 0); // internal attr
        PutU32(central, 0); // external attr
        PutU32(central, offset);
        central += e.name;
    }

    const uint32_t centralOffset = static_cast<uint32_t>(local.size());
    const uint32_t centralSize = static_cast<uint32_t>(central.size());

    std::string eocd;
    PutU32(eocd, 0x06054b50);
    PutU16(eocd, 0);
    PutU16(eocd, 0);
    PutU16(eocd, static_cast<uint16_t>(entries.size()));
    PutU16(eocd, static_cast<uint16_t>(entries.size()));
    PutU32(eocd, centralSize);
    PutU32(eocd, centralOffset);
    PutU16(eocd, 0);

    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) {
        return false;
    }
    out.write(local.data(), static_cast<std::streamsize>(local.size()));
    out.write(central.data(), static_cast<std::streamsize>(central.size()));
    out.write(eocd.data(), static_cast<std::streamsize>(eocd.size()));
    return static_cast<bool>(out);
}

bool ReadZip(const std::string& path, std::vector<ZipEntry>& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return false;
    }
    std::string data((std::istreambuf_iterator<char>(in)),
                     std::istreambuf_iterator<char>());
    if (data.size() < 22) {
        return false;
    }
    // 从尾部找 EOCD 签名。
    size_t eocd = std::string::npos;
    const size_t minPos = data.size() >= 22 ? data.size() - 22 : 0;
    for (size_t p = data.size() - 22 + 1; p-- > minPos;) {
        if (GetU32(data, p) == 0x06054b50) {
            eocd = p;
            break;
        }
    }
    if (eocd == std::string::npos) {
        return false;
    }
    const uint16_t count = GetU16(data, eocd + 10);
    uint32_t p = GetU32(data, eocd + 16); // central dir offset
    out.clear();
    for (uint16_t i = 0; i < count; ++i) {
        if (p + 46 > data.size() || GetU32(data, p) != 0x02014b50) {
            return false;
        }
        const uint16_t method = GetU16(data, p + 10);
        const uint32_t compSize = GetU32(data, p + 20);
        const uint16_t nameLen = GetU16(data, p + 28);
        const uint16_t extraLen = GetU16(data, p + 30);
        const uint16_t commentLen = GetU16(data, p + 32);
        const uint32_t localOffset = GetU32(data, p + 42);
        const std::string name = data.substr(p + 46, nameLen);
        p += 46u + nameLen + extraLen + commentLen;

        if (method != 0) {
            return false; // 仅支持 store
        }
        if (localOffset + 30 > data.size() ||
            GetU32(data, localOffset) != 0x04034b50) {
            return false;
        }
        const uint16_t lNameLen = GetU16(data, localOffset + 26);
        const uint16_t lExtraLen = GetU16(data, localOffset + 28);
        const size_t dataStart = localOffset + 30u + lNameLen + lExtraLen;
        if (dataStart + compSize > data.size()) {
            return false;
        }
        ZipEntry e;
        e.name = name;
        e.content = data.substr(dataStart, compSize);
        out.push_back(std::move(e));
    }
    return true;
}

} // namespace backup
} // namespace xime
