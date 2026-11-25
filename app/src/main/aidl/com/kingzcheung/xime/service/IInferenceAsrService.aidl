package com.kingzcheung.xime.service;

import com.kingzcheung.xime.service.IInferenceAsrCallback;

interface IInferenceAsrService {
    /**
     * 加载 ASR 模型并开始识别。返回会话号（>0）；0 表示模型缺失/加载失败。
     * 新会话会顶替仍在进行的旧会话，旧会话号随即失效。
     */
    long startAsr(String modelDir, IInferenceAsrCallback callback);
    /** 推送 PCM 音频数据（16k/16bit/mono），服务端内部流式识别。会话号不是当前会话时丢弃。 */
    void pushAsrAudio(long sessionId, in byte[] audioData);
    /** 结束识别，返回最终识别文本。会话号不是当前会话时返回空串且不影响当前会话。 */
    String stopAsr(long sessionId);
    /** 取消识别会话。会话号不是当前会话时忽略。 */
    void cancelAsr(long sessionId);
    /** 释放 ASR 模型（无条件，同时作废任何进行中的会话） */
    void releaseAsr();
    /**
     * "保持引擎常驻"开关：true 时服务端不做 60s 空闲自动释放，
     * 模型常驻内存（用户已明确选择以内存换响应速度）；false 恢复默认回收。
     */
    oneway void setKeepModelAlive(boolean keepAlive);
}
