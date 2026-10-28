<script setup lang="ts">
import AdminForm from "./AdminForm.vue";
const emit = defineEmits<{ submit: [event: SubmitEvent] }>();
</script>
<template>
  <AdminForm class="admin-filter-bar" @submit="emit('submit', $event)">
    <slot />
    <div v-if="$slots.actions" class="admin-filter-actions">
      <slot name="actions" />
    </div>
  </AdminForm>
</template>
