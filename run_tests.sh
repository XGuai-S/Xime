#!/bin/bash

echo "================================"
echo "Xime 测试运行脚本"
echo "================================"
echo ""

case "$1" in
    "unit")
        echo "运行单元测试..."
        ./gradlew :app:testDebugUnitTest
        ;;
    "instrumented")
        echo "运行仪器测试（需要连接设备或模拟器）..."
        ./gradlew :app:connectedDebugAndroidTest
        ;;
    "all")
        echo "运行所有测试..."
        ./gradlew :app:testDebugUnitTest
        ./gradlew :app:connectedDebugAndroidTest
        ;;
    "coverage")
        echo "生成测试覆盖率报告..."
        ./gradlew :app:createDebugCoverageReport
        echo "覆盖率报告位置: app/build/reports/coverage/debug/index.html"
        ;;
    "clean")
        echo "清理测试结果..."
        ./gradlew clean
        ;;
    *)
        echo "使用方法:"
        echo "  $0 unit          - 运行单元测试"
        echo "  $0 instrumented  - 运行仪器测试"
        echo "  $0 all           - 运行所有测试"
        echo "  $0 coverage      - 生成覆盖率报告"
        echo "  $0 clean         - 清理测试结果"
        echo ""
        echo "示例:"
        echo "  $0 unit"
        exit 1
        ;;
esac

echo ""
echo "测试完成！"