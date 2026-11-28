// xime-deploy.exe [dataDir] [userDir] —— 离线完成 librime 部署（生成 build/*.bin）。
// 不删除用户数据；用于安装后预热，避免首次打字等待部署。
#include "../src/core/RimeEngine.h"

#include <windows.h>

#include <cstdio>
#include <string>

int main(int argc, char** argv) {
    const std::string data = argc > 1 ? argv[1] : "data";
    std::string user;
    if (argc > 2) {
        user = argv[2];
    } else {
        char buf[1024] = {0};
        if (GetEnvironmentVariableA("LOCALAPPDATA", buf, sizeof(buf)) == 0) {
            std::printf("DEPLOY_FAIL: no LOCALAPPDATA\n");
            return 1;
        }
        user = std::string(buf) + "\\Xime";
    }

    auto& engine = xime::core::RimeEngine::Instance();
    if (!engine.Initialize(user, data)) {
        std::printf("DEPLOY_FAIL: init\n");
        return 1;
    }
    if (engine.IsDeployed()) {
        std::printf("DEPLOY_SKIP: already deployed\n");
        return 0;
    }
    std::printf("DEPLOY_START\n");
    if (!engine.StartMaintenance(true)) {
        std::printf("DEPLOY_FAIL: start_maintenance\n");
        return 1;
    }
    for (int i = 0; i < 3600 && engine.IsMaintaining(); ++i) {
        Sleep(100);
    }
    if (engine.IsMaintaining()) {
        std::printf("DEPLOY_FAIL: timeout\n");
        return 1;
    }
    if (!engine.IsDeployed()) {
        std::printf("DEPLOY_FAIL: no artifacts\n");
        return 1;
    }
    std::printf("DEPLOY_OK\n");
    return 0;
}
