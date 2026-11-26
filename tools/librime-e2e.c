// librime 1.18.0 端到端测试：部署 schema + 输入拼音 + 打印候选
// 编译：g++ -o e2e e2e.c -I<SRC>/src -I<SRC>/include -L<BUILD>/lib -lrime -Wl,-rpath,<BUILD>/lib
// 运行前 mkdir -p /root/rime-e2e/{share/rime,user_data,log}，把 schema/dict 数据放入 share/rime
#include <stdio.h>
#include <rime_api.h>

static void on_msg(void* c, size_t t, const char* msg, const char* e) {
  printf("[msg] %s\n", msg);
}

int main() {
  RimeApi* rime = rime_get_api();
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = "/root/rime-e2e/share/rime";
  traits.user_data_dir = "/root/rime-e2e/user_data";
  traits.app_name = "rime.e2e";
  traits.log_dir = "/root/rime-e2e/log";
  traits.min_log_level = 1;
  rime->setup(&traits);
  rime->set_notification_handler(&on_msg, NULL);
  rime->initialize(NULL);
  rime->start_maintenance(True);
  rime->join_maintenance_thread();

  RimeSessionId s = rime->create_session();
  RimeSchemaList sl = {0};
  if (rime->get_schema_list(&sl)) {
    printf("schemas=%zu\n", sl.size);
    rime->free_schema_list(&sl);
  }
  rime->select_schema(s, "luna_pinyin");
  char cur[100] = {0};
  rime->get_current_schema(s, cur, sizeof(cur));
  printf("schema=[%s]\n", cur);

  const char* keys = "zhang";
  for (const char* p = keys; *p; ++p) rime->process_key(s, (int)*p, 0);
  printf("candidates for '%s':\n", keys);
  RimeCandidateListIterator it = {0};
  if (rime->candidate_list_begin(s, &it)) {
    while (rime->candidate_list_next(&it))
      printf("  %d. %s\n", it.index + 1, it.candidate.text);
    rime->candidate_list_end(&it);
  }
  rime->destroy_session(s);
  rime->finalize();
  return 0;
}
