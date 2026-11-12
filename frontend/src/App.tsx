import { Link, Navigate, Route, Routes } from "react-router";
import { RequireAuth } from "./auth/RequireAuth";
import { Layout } from "./components/Layout";
import { ContractDetailPage } from "./pages/ContractDetailPage";
import { ContractListPage } from "./pages/ContractListPage";
import { LoginPage } from "./pages/LoginPage";
import { UploadPage } from "./pages/UploadPage";

/** The route table. The router and the auth provider are supplied by main.tsx (or by tests). */
export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/contracts" element={<ContractListPage />} />
        <Route path="/contracts/new" element={<UploadPage />} />
        <Route path="/contracts/:id" element={<ContractDetailPage />} />
      </Route>
      <Route path="/" element={<Navigate to="/contracts" replace />} />
      <Route
        path="*"
        element={
          <main id="main" className="page">
            <h1>Page not found</h1>
            <p>
              <Link to="/contracts">Go to the contract list</Link>
            </p>
          </main>
        }
      />
    </Routes>
  );
}
