// librime 1.17 vs 1.18 性能对比基准：部署时间 + 批量候选查询耗时
// 两版 API 签名相同，基准可共用。编译见 tools/librime-bench.sh
#include <stdio.h>
#include <time.h>
#include <rime_api.h>

static double now_ms() {
  struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec*1000.0 + ts.tv_nsec/1e6;
}
static void on_msg(void* c, size_t t, const char* m, const char* e) {}

int main(int argc, char** argv) {
  const char* ud = argc>1 ? argv[1] : "/tmp/rime-bench-user";
  RimeApi* rime = rime_get_api();
  RIME_STRUCT(RimeTraits, t);
  t.shared_data_dir = "/tmp/rime-bench-share";
  t.user_data_dir = ud;
  t.app_name = "rime.bench";
  t.min_log_level = 0;
  double t0 = now_ms();
  rime->setup(&t);
  rime->set_notification_handler(&on_msg, NULL);
  rime->initialize(NULL);
  rime->start_maintenance(True);
  rime->join_maintenance_thread();
  double t_deploy = now_ms() - t0;

  RimeSessionId s = rime->create_session();
  rime->select_schema(s, "luna_pinyin");
  for (int i=0;i<3;i++){ rime->clear_composition(s); rime->process_key(s,'n',0); rime->process_key(s,'i',0); }

  const char* inputs[] = {"ni","hao","nihao","zhang","zhangsan","xie","xiexie","wo","shi",
                          "zhong","guo","a","z","zh","zha","ximeshuru","shurufa","pinyin",
                          "zhongwen","jianpan"};
  const int N = (int)(sizeof(inputs)/sizeof(inputs[0]));
  const int ROUNDS = 20;
  double t0q = now_ms();
  long total_cand = 0;
  for (int r=0;r<ROUNDS;r++) for (int k=0;k<N;k++) {
    rime->clear_composition(s);
    const char* p = inputs[k];
    for (;*p;++p) rime->process_key(s, (int)*p, 0);
    RimeCandidateListIterator it = {0};
    if (rime->candidate_list_begin(s, &it)) {
      while (rime->candidate_list_next(&it)) total_cand++;
      rime->candidate_list_end(&it);
    }
  }
  double t_query = now_ms() - t0q;
  printf("DEPLOY_MS=%.1f\nQUERY_MS_TOTAL=%.1f\nQUERY_MS_PER_INPUT=%.3f\nQUERIES=%d\nCANDIDATES=%ld\n",
         t_deploy, t_query, t_query/(ROUNDS*N), ROUNDS*N, total_cand);
  rime->destroy_session(s); rime->finalize();
  return 0;
}
