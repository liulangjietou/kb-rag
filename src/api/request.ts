import axios, { type AxiosError, type AxiosRequestConfig } from 'axios';
import { message } from 'antd';
import { clearToken, getToken } from './authStorage';
import type { ApiResult } from './types';

// The login endpoint must never trigger the 401 redirect loop (a wrong password
// legitimately returns 401 and should just surface as a form error).
const LOGIN_PATH = '/auth/login';

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 30_000,
});

client.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

client.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiResult<unknown>>) => {
    const status = error.response?.status;
    const requestId = error.response?.data?.request_id;
    const isLoginRequest = error.config?.url?.includes(LOGIN_PATH) ?? false;

    if (status === 401 && !isLoginRequest) {
      // Session expired or token invalid: drop local auth state and bounce to login.
      clearToken();
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }

    const backendMessage = error.response?.data?.message || error.message || '网络请求失败';
    const suffix = requestId ? ` (request_id: ${requestId})` : '';
    message.error(`${backendMessage}${suffix}`);
    return Promise.reject(error);
  },
);

/** Unwraps the `{code, message, data, request_id}` envelope and rejects on business errors. */
async function unwrap<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await client.request<ApiResult<T>>(config);
  const body = response.data;
  if (body.code !== 'OK') {
    const suffix = body.request_id ? ` (request_id: ${body.request_id})` : '';
    message.error(`${body.message}${suffix}`);
    throw new Error(`${body.code}: ${body.message}`);
  }
  return body.data;
}

export function apiGet<T>(url: string, params?: object): Promise<T> {
  return unwrap<T>({ url, method: 'GET', params });
}

export function apiPost<T>(url: string, data?: unknown): Promise<T> {
  return unwrap<T>({ url, method: 'POST', data });
}

export function apiDelete<T>(url: string): Promise<T> {
  return unwrap<T>({ url, method: 'DELETE' });
}

/** Multipart upload helper; the browser sets the multipart boundary automatically. */
export function apiUpload<T>(url: string, formData: FormData): Promise<T> {
  return unwrap<T>({ url, method: 'POST', data: formData });
}

export default client;
