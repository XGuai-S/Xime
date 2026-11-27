#pragma once
#include <onnxruntime_c_api.h>
#include <stdint.h>

const OrtApi* OnnxGetApi();
OrtEnv* OnnxGetSharedEnv();
void OnnxReleaseSharedEnv();

// 让 session 使用共享 env 的全局线程池，并关闭空闲自旋（移动端省电）。
// 必须在 CreateSession 之前对 OrtSessionOptions 调用。
bool OnnxApplySharedThreadPoolOptions(OrtSessionOptions* options);

// Try to enable NNAPI execution provider via dlsym.
// Returns true if NNAPI was enabled, false if not available (CPU fallback).
bool OnnxTryEnableNnapi(OrtSessionOptions* options);

// Append the CPU execution provider as the explicit fallback.
// onnxruntime partitions the graph so nodes NNAPI doesn't support run on CPU.
// Returns true if the CPU EP was appended.
bool OnnxTryEnableCpuFallback(OrtSessionOptions* options);
