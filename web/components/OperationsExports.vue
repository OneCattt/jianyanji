<script setup lang="ts">
defineProps<{ permissions: string[] }>();
</script>
<template>
  <div class="operations-exports" aria-label="运营资料导出">
    <a-button
      html-type="button"
      v-if="permissions.includes('onboarding:manage')"
      href="/api/admin/exports/operations/qualifications"
      download
      >导出资格与归属</a-button
    ><template v-if="permissions.includes('orders:manage')"
      ><a-button
        html-type="button"
        href="/api/admin/exports/operations/orders"
        download
        >导出订单履约</a-button
      ><a-button
        html-type="button"
        href="/api/admin/exports/operations/aftersales"
        download
        >导出售后</a-button
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
small {
  color: var(--admin-muted);
}
</style>
