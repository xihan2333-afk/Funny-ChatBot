<template>
  <div class="auth-page">
    <el-card class="auth-card" shadow="always">
      <template #header>
        <div class="card-title">Funny-ChatBot 管理平台</div>
      </template>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="handleSubmit">
        <el-form-item label="管理员账号" prop="username">
          <el-input v-model="form.username" placeholder="请输入管理员账号" autocomplete="username"/>
        </el-form-item>
        <el-form-item label="登录口令" prop="password">
          <el-input
              v-model="form.password"
              type="password"
              show-password
              placeholder="请输入登录口令"
              autocomplete="current-password"
              @keyup.enter="handleSubmit"
          />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" @click="handleSubmit">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {reactive, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import type {FormInstance, FormRules} from 'element-plus'
import {useAuthStore} from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: '',
})

const rules: FormRules = {
  username: [{required: true, message: '请输入账号', trigger: 'blur'}],
  password: [{required: true, message: '请输入口令', trigger: 'blur'}],
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const res = await authStore.login(form.username.trim(), form.password)
    if (res.mustChange) {
      await router.replace('/auth/change-password')
    } else {
      const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview'
      await router.replace(redirect || '/overview')
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1d2129 0%, #2c3e50 100%);
}

.auth-card {
  width: 400px;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}
</style>
