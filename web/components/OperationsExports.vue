<script setup lang="ts">
defineProps<{ permissions: string[] }>();
</script>
<template>
  <div class="operations-exports" aria-label="运营资料导出">
    <a
      v-if="permissions.includes('onboarding:manage')"
      href="/api/admin/exports/operations/qualifications"
      download
      >导出资格与归属</a
    ><template v-if="permissions.includes('orders:manage')"
      ><a href="/api/admin/exports/operations/orders" download>导出订单履约</a
      ><a href="/api/admin/exports/operations/aftersales" download
        >导出售后</a
      ></template
    ><small>最近200条以内，金额单位为分</small>
  </div>
</template>
<style scoped>
.operations-exports {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin: 12px 0;
  font-size: 13px;
}
a {
  color: var(--admin-accent);
}
small {
  color: var(--admin-muted);
}
</style>
