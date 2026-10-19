<script setup lang="ts">
import AdminView from "./components/AdminView.vue";
import CustomerView from "./components/CustomerView.vue";
const admin = window.location.pathname.startsWith("/admin");
</script>
<template><AdminView v-if="admin" /><CustomerView v-else /></template>
