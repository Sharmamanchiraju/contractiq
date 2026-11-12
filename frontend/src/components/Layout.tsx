import { Link, Outlet, useNavigate } from "react-router";
import { can, useAuth } from "../auth/AuthContext";

/** The frame around every signed-in page: skip link, header with the user, navigation and sign-out. */
export function Layout() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="app-header">
        <Link to="/contracts" className="brand">
          ContractIQ
        </Link>
        <nav aria-label="Main">
          <Link to="/contracts">Contracts</Link>
          {can(user?.role, "upload") ? <Link to="/contracts/new">Upload contract</Link> : null}
        </nav>
        <div className="user">
          <span>
            {user?.username} <span className="badge badge-neutral">{user?.role}</span>
          </span>
          <button
            type="button"
            className="button button-small"
            onClick={() => {
              signOut();
              navigate("/login");
            }}
          >
            Sign out
          </button>
        </div>
      </header>
      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>
    </>
  );
}
