<script setup lang="ts">
import { useAdminUserNames } from "../admin-user-names";
defineProps<{ id?: number | string | null; name?: string }>();
const users = useAdminUserNames();
</script>
<template>
  <span class="admin-user-name"
    >{{ users.label(id, name)
    }}<a-button
      v-if="!name && users.failed(id)"
      type="link"
      size="small"
      @click="users.retry(id)"
      >重试</a-button
    ></span
  >
</template>
<style scoped>
.admin-user-name {
  overflow-wrap: anywhere;
}
</style>
