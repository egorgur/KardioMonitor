import { type FormEvent, useState } from "react";
import { ApiError, type Clinician, api } from "./api";

/** Вход врача (ТР-11). Аккаунт создаёт администратор сервера. */
export function SignIn({ onSignedIn }: { onSignedIn: (clinician: Clinician) => void }) {
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [isSending, setSending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSending(true);
    setError(null);
    try {
      onSignedIn(await api.signIn(login, password));
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Сервер недоступен");
      setPassword("");
    } finally {
      setSending(false);
    }
  }

  return (
    <form className="notice sign-in" onSubmit={submit}>
      <h2>Вход для врача</h2>
      <label>
        Логин
        <input value={login} onChange={(e) => setLogin(e.target.value)} autoComplete="username" required autoFocus />
      </label>
      <label>
        Пароль
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          required
        />
      </label>
      {error && (
        <p className="sign-in-error" role="alert">
          {error}
        </p>
      )}
      <button type="submit" disabled={isSending}>
        {isSending ? "Вход…" : "Войти"}
      </button>
    </form>
  );
}
